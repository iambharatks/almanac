# 09 · Docker, Config & Deployment

> ⭐ **The one idea:** The **same image** should run everywhere — dev, staging, production. Only the **injected environment** differs (hostnames, secrets). Code goes in the image; config never does.

---

## 1. Image vs container

**Image** = the template (a class). **Container** = a running instance of it (an object). `docker build` creates an image — nothing appears under **Containers** until you `docker run` one. Check the **Images** tab / `docker images`, not Containers, right after a build.

## 2. The Dockerfile

```dockerfile
FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev          # deps BEFORE source → layer caching
COPY src/ ./src/
COPY alembic/ ./alembic/
COPY alembic.ini ./
CMD ["uv", "run", "uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

- **Deps copied and installed before source** — Docker caches each layer; code-only edits reuse the cached dependency layer → fast rebuilds.
- **`--host 0.0.0.0`** — uvicorn defaults to `127.0.0.1` (only reachable *inside* the container); `0.0.0.0` listens on all interfaces so the port mapping works.
- **`.dockerignore`** excludes `.venv/`, `__pycache__/`, `.git/`, and critically **`.env`** — secrets must never be baked into image layers (extractable by anyone with the image).

## 3. Multiple workers — using all the cores

One `uvicorn` process = one core used (the GIL). To use more cores, run more **processes** via **gunicorn**:

```bash
uv add gunicorn
```
```dockerfile
CMD ["uv", "run", "gunicorn", "src.main:app", \
     "--workers", "4", \
     "--worker-class", "uvicorn.workers.UvicornWorker", \
     "--bind", "0.0.0.0:8000"]
```

**What happens:** gunicorn (**master process**) forks N **worker processes**, each its own uvicorn + its own event loop, all bound to the same port — the OS load-balances connections across them. Workers run **in parallel** across cores; each worker's loop handles many requests **concurrently**. Rule of thumb: `(2 × cores) + 1`, but don't over-provision a small container.

**Each worker = separate process = own engine, own pool, own Redis client, own event loop.** This is exactly the "N workers → N pools → 1 shared server" model — nothing in the app code changes, because shared state (cache, rate-limit counters, rows) already lives in Redis/Postgres, not in any process.

**Verifying workers are actually up:**
```bash
docker compose logs app --tail 50      # "Booting worker with pid: N" × 4
docker compose exec app ps aux         # 1 master + N worker processes
```
**Proving requests are actually spread across them** — temporarily return `os.getpid()` from an endpoint and hit it in a loop; distinct PIDs across responses = real round-robin across processes, not one process context-switching.
**The definitive test:** hammer a **rate-limited** endpoint as one user faster than one worker could serve — if the limit is enforced correctly (10 successes then 429s) even though 4 *different* processes are handling requests, that's proof the Redis-backed counter design is correct under real horizontal scaling, not just in theory.

## 4. Compose networking — the hostname shift

Inside a container, `localhost` means **that container**, not your machine. Containers reach each other by **service name** on the compose network:

| Local (`uv run ...`) | Inside compose |
|---|---|
| `POSTGRES_HOST=localhost` | `POSTGRES_HOST=postgres` |
| `REDIS_HOST=localhost` | `REDIS_HOST=redis` |
| `RABBITMQ_HOST=localhost` | `RABBITMQ_HOST=rabbitmq` |

This is *the* main gotcha when containerizing — `.env` must say `localhost` for local runs; compose overrides the three hostnames for containers.

## 5. Config refactor — component-based settings

**Problem:** one `.env` serves two consumers (docker-compose needs `POSTGRES_USER`/`PASSWORD`/etc.; the app needs assembled URLs) → Pydantic's `extra="forbid"` (default) rejects vars with no matching field → `Extra inputs are not permitted`.

**Quick patch:** `extra="ignore"` in `model_config` — works, but hides the mismatch (logged as tech debt if used).

**Proper fix — store components, build URLs with `@property`:**
```python
class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env")
    postgres_user: str
    postgres_password: str
    postgres_db: str
    postgres_host: str = "localhost"
    postgres_port: int = 5432
    # ... redis_host, rabbitmq_user/password/host ...

    @property
    def database_url(self) -> str:
        return f"postgresql+asyncpg://{self.postgres_user}:{self.postgres_password}@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
```
**Why better:** one source of truth per value — compose *and* app read the **same** `POSTGRES_PASSWORD`, can't drift. No `extra="ignore"` needed. Compose's `app` service then overrides just **three hostnames**, nothing else. Nothing else in the codebase changes — `settings.database_url` still "looks like" an attribute everywhere it's used (the `@property` payoff).

## 6. Alembic inside Docker — a separate gotcha

`alembic.ini`'s `sqlalchemy.url` is a **static, hardcoded string**, unrelated to your `Settings` class or `.env`. It was fine locally (pointed at `localhost`), but breaks inside a container — Alembic tries `localhost:5432`, finds nothing there, `ConnectionRefusedError`, and **no tables ever get created** ("no relations found" is the downstream symptom of every migration attempt failing at the connection step).

**Fix — make `env.py` use the app's own dynamic settings:**
```python
# alembic/env.py
from src.config import settings
config = context.config
config.set_main_option("sqlalchemy.url", settings.database_url)
```
Now Alembic resolves the same way the app does — `postgres` inside containers, `localhost` locally. Run migrations **inside** the running container so they inherit its environment:
```bash
docker compose exec app alembic upgrade head
docker compose exec postgres psql -U app -d urlshortener -c "\dt"   # verify tables exist
```

## 7. `env_file` vs `${VAR}` substitution — different mechanisms

| | Runs when | Purpose |
|---|---|---|
| `env_file: - .env` | **runtime** — injects vars into the container | app config |
| `${VAR}` | **parse time** — fills in the compose file itself | compose service config (postgres user/pass, etc.) |

`env_file` does **not** feed `${...}` substitution — they're unrelated. `docker compose config` shows what actually resolved; if a var shows blank, check `.env` exists in the project root, no spaces around `=`, no quotes, and **no CRLF line endings** (`cat -A .env` → `^M$` = CRLF → `sed -i 's/\r$//' .env`).

**Committed compose files must never contain literal secrets** — only `${VAR}` references or `env_file:`. A throwaway local dev password hardcoded consistently across services is a pragmatic exception, not a real secret.

## 8. Secrets in production — AWS Secrets Manager

**The app code doesn't change** — it still just reads env vars. The *platform* puts them there:

1. Store the secret in **Secrets Manager**/SSM.
2. **ECS task definition** references it by ARN in a `secrets:` block; non-secret values go in a plain `environment:` block.
3. **ECS fetches and injects it as an env var before the container starts.**
4. `pydantic-settings` reads it exactly like it reads `.env` locally.

Benefits: secret never touches image/code/repo (only an ARN reference does), access controlled by **IAM**, audit logs, rotation without redeploying.

| | Local | Production |
|---|---|---|
| Non-secret config | `.env` | env vars in task definition |
| Secrets | `.env` (gitignored) | Secrets Manager → injected as env var |
| Databases | Docker containers | **managed services** (RDS / ElastiCache / Amazon MQ) — backups/failover/patching handled |
| Hostnames | `postgres`, `redis` | RDS / ElastiCache endpoints |
| Startup ordering | `depends_on` | health checks + app-side retries |

## 9. Modular monolith vs microservices

| | This project | True microservices |
|---|---|---|
| Deployable units | 1 app + 1 worker process, **same repo** | N independent services, own repos/pipelines |
| Data | one shared Postgres | each service **owns its own** database |
| Communication | in-process calls (router→service→repo) | network calls / queues **between services** |
| Deployment | whole app together | each service independently |

> This is a **modular monolith** — one FastAPI service with clean internal separation by domain (`auth/`, `urls/`), plus an event-driven **worker process** decoupled via RabbitMQ. The consumer is architecturally microservices-*flavored* (independent process, communicates only through messages) but shares the codebase and isn't independently deployed — one step short of a real microservice. RabbitMQ is exactly the infrastructure you'd reuse to actually split this later.

---

## 🎤 Interview answers

**"How is your app deployed / would you deploy it?"**
> Containerized with a multi-stage-cached Dockerfile, run via gunicorn managing several uvicorn worker processes so it uses all available cores, not just one. Locally that's docker-compose with Postgres, Redis, and RabbitMQ as sibling containers; in production I'd swap those for managed services — RDS, ElastiCache, Amazon MQ — behind ECS, with secrets injected from Secrets Manager rather than an env file. The same image runs in both places; only the injected configuration differs.

**"Is this microservices?"**
> No — it's a modular monolith. One deployable FastAPI service with clean domain separation internally, plus a separate consumer process that only communicates through RabbitMQ events rather than direct calls. That messaging pattern is deliberately the same one you'd use to split it into real microservices later, each with its own database and deployment pipeline, but right now it's one codebase and one shared database.

**"How do multiple workers stay consistent for things like rate limiting?"**
> Each worker is a separate OS process with its own connection pool, but the actual state — cache entries, rate-limit counters — lives in Redis, not in any process. So no matter how many workers or machines are handling requests, they're all reading and writing the same external source of truth.

---

## ✅ Gotcha checklist

- [ ] `.dockerignore` excludes `.env` — inject secrets at runtime, never bake them in
- [ ] `--host 0.0.0.0` inside containers
- [ ] `localhost` → service name (`postgres`, `redis`, `rabbitmq`) inside compose
- [ ] gunicorn + UvicornWorker for multi-core; verify with `ps aux` / distinct PIDs
- [ ] `alembic.ini`'s URL is static — point `env.py` at `settings.database_url` instead
- [ ] Run migrations **inside** the container (`docker compose exec app alembic upgrade head`)
- [ ] `env_file` (runtime) ≠ `${VAR}` (parse-time compose substitution)
- [ ] Committed compose files: no literal secrets
- [ ] Component-based settings + `@property` URLs > `extra="ignore"`
- [ ] Production secrets: Secrets Manager → ECS → injected env var, code unchanged
