# 11 · Troubleshooting Quick Reference

> ⭐ **The one idea:** Almost every error below is one of four categories — **wrong host** (localhost vs service name), **wrong timing** (something not ready yet), **wrong loop** (async resource crossing event loops), or **static config drift** (a hardcoded value that stopped matching reality). Diagnose by asking which of the four it is first.

---

## App / FastAPI / routing

| Issue | Cause | Fix |
|---|---|---|
| `uvicorn: could not import module "main"` | module path needs **dots**, matching file location | `uv run uvicorn src.main:app --reload` |
| `TypeError: FastAPI.__init__() takes 1 positional arg` | passed title positionally | `FastAPI(title="...")` — keyword |
| `401` on a redirect that should be public/404 | catch-all route or a stray auth dependency | check route registration order + endpoint's `Depends()` list |
| Catch-all route swallows specific ones | `GET /{short_code}` registered **before** `/urls` etc. | register catch-alls **last** |

## Auth

| Issue | Cause | Fix |
|---|---|---|
| `ValueError: password cannot be longer than 72 bytes` | `passlib` unmaintained, incompatible with bcrypt ≥4.1 | use `bcrypt` directly, truncate `[:72]` |
| Postman login `422` | login needs **form data**, not JSON | Body → `x-www-form-urlencoded`; register stays JSON |
| `401 Unauthorized` on `POST /urls` | endpoint requires `get_current_user` | send `Authorization: Bearer <token>` (Swagger **Authorize**) |
| `InsecureKeyLengthWarning: HMAC key is 25 bytes` | JWT secret < 32 bytes (RFC 7518) | `python -c "import secrets; print(secrets.token_urlsafe(32))"` — never hand-type |

## Database / Alembic

| Issue | Cause | Fix |
|---|---|---|
| `duplicate key violates unique constraint ix_urls_short_code` | random short-code collided | generate → check → retry loop (random ≠ unique) |
| `Target database is not up to date` | a generated migration isn't applied | `alembic upgrade head` — **don't** regenerate |
| `column "user_id" contains null values` | NOT NULL added to a populated table | dev: delete rows + re-migrate · prod: nullable → backfill → NOT NULL |
| Alembic `ConnectionRefusedError` inside Docker, "no relations found" | `alembic.ini`'s `sqlalchemy.url` is **static**, points at `localhost` | `env.py`: `config.set_main_option("sqlalchemy.url", settings.database_url)`; run via `docker compose exec app alembic upgrade head` |

## Docker / WSL / networking

| Issue | Cause | Fix |
|---|---|---|
| `docker: command not found` (WSL) | Docker Desktop not running, or WSL integration off | start Docker Desktop → Settings → Resources → WSL Integration → enable distro |
| `permission denied ... docker.sock` | user not in `docker` group | `sudo usermod -aG docker $USER` + `wsl --shutdown` |
| Compose started only 3 containers, no app | `app` service missing/mis-indented | add it; `docker compose config --services` should list 4 |
| Image built but "not in Docker Desktop" | looking at **Containers** tab | check **Images** tab / `docker images` |
| Standalone container: pydantic `Field required` | `.env` (rightly) excluded from image | `docker run --env-file .env ...` / compose `env_file:` |
| `Bind for 0.0.0.0:8000 failed: port already allocated` | old container still running (**Ctrl+Z** suspends, doesn't stop) | `docker ps` → `docker stop <name>`; use **Ctrl+C** to stop |
| `ConnectionRefusedError` reaching Postgres from inside a container | app trying `localhost` instead of the service name | set `POSTGRES_HOST=postgres` for the containerized app; verify with `docker compose exec app env \| grep POSTGRES_HOST` |
| Same, but config already correct | Postgres container not **ready** yet (`depends_on` only waits for start, not readiness) | add a `healthcheck` + `condition: service_healthy`; check `docker compose logs postgres` for "ready to accept connections" |
| Can't see live request logs | compose running detached (`-d`) | `docker compose logs -f app` |

## Config / secrets

| Issue | Cause | Fix |
|---|---|---|
| `WARN: "POSTGRES_PASSWORD" variable is not set` | `${VAR}` substitution can't resolve | check `.env` exists in cwd, no spaces around `=`, no quotes, **no CRLF** (`cat -A .env` → `^M$` → `sed -i 's/\r$//' .env`); verify with `docker compose config` |
| Pydantic `Extra inputs are not permitted` | `.env` has compose-only vars with no `Settings` field | proper fix: component fields + `@property` URLs · quick patch: `extra="ignore"` |
| `ConnectionRefusedError` running **locally** (not in Docker) | `.env` had `POSTGRES_HOST=postgres` (a Docker-only hostname) | `.env` → `localhost`; compose overrides to `postgres` only for containers |

## Testing

| Issue | Cause | Fix |
|---|---|---|
| `ModuleNotFoundError: No module named 'src'` | pytest's `sys.path` doesn't include the project root | `pythonpath = ["."]` under `[tool.pytest.ini_options]`, or `uv pip install -e .` |
| `InterfaceError: another operation is in progress` | pooled connections created at import cross per-test event loops | `create_async_engine(URL, poolclass=NullPool)` + ensure the test DB exists |
| `RuntimeError: Event loop is closed` | module-global Redis/RabbitMQ client outlives its loop | mock it (`monkeypatch`/`dependency_overrides`), or move to lifespan + DI |
| `AttributeError: module '...' has no attribute 'redis_client'` | patch target name doesn't match the actual import | check the real import; patch the name that exists, or override the dependency instead |
| `TypeError: '>' not supported between AsyncMock and int` | mock method returned a mock, code compared it to an int | set the specific `.return_value` (e.g. `mock_redis.incr.return_value = 1`) |

## Git / environment

| Issue | Cause | Fix |
|---|---|---|
| Files in `.gitignore` still committed | `.gitignore` is **not retroactive** | `git rm -r --cached .` → `git add .` (`--cached` doesn't delete from disk) |
| "Password authentication is not supported" | GitHub dropped password auth | use a PAT or SSH key |
| SSH key "wrong format" | copied the **private** key, or paste broke lines | use `id_ed25519.pub` (one line, starts `ssh-ed25519`) |
| `uv: command not found` in a `MINGW64` prompt | that's **Git Bash (Windows)**, not WSL Ubuntu | work from a WSL terminal (VS Code `><` → Connect to WSL) |

---

## The four root-cause categories, restated

1. **Wrong host** — `localhost` inside a container means the container itself, not the machine or another service. Symptom: `ConnectionRefusedError`. Fix: use the compose **service name**.
2. **Wrong timing** — a dependency (Postgres, a migration) hasn't finished starting when something tries to use it. Symptom: works eventually, fails on first try. Fix: healthchecks, retries, or just re-running once things settle.
3. **Wrong loop** — an async resource created outside the loop currently running. Symptom: `InterfaceError` / `Event loop is closed`, almost always in **tests**. Fix: `NullPool`, mocking, or lifespan+DI.
4. **Static config drift** — a hardcoded value (an old `alembic.ini` URL, a stale patch-string, a password baked into one place but not another) silently stops matching reality as the project evolves. Symptom: confusing errors that "used to work." Fix: single source of truth (settings object, `env_file`, function references over strings).

When something breaks, ask **which of the four** before diving into the stack trace — it narrows the search dramatically.
