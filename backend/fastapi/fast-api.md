# FastAPI & Backend — Notes
*Framework, web, DB, infra deep-dives, as Q&A. Crisp, technical. Built via the URL-shortener project (Slices A–D).*

---

## The stack & request lifecycle

**Q: What is a backend / the request path?**
A program that runs continuously, handling HTTP request → work → response. Path: browser → **uvicorn** (ASGI server) → **FastAPI** (routing + DI) → **Pydantic** (validate) → router/service/repository → **SQLAlchemy async** + **asyncpg** → **PostgreSQL**; plus **Redis** (cache), **RabbitMQ** (events). Alembic builds the schema; JWT proves identity; Docker runs it all.

**Q: Node.js vs Spring Boot vs FastAPI?**
Node = libuv event loop, JS, I/O/real-time. Spring Boot = JVM, opinionated platform, enterprise/fintech, heavy. FastAPI = Python, async event loop, lightweight, type-hint validation, AI-adjacent. Coding concepts transfer across all three.

**Q: What does "opinionated" mean?**
Opinionated = framework makes structural decisions for you (conventions, "the right way") → consistency, steeper curve (Spring Boot). Unopinionated = you assemble it (Express). FastAPI is in the middle — opinionated only about type hints/validation.

**Q: ASGI server vs embedded server?**
ASGI (Python): a *separate, swappable* program (uvicorn) linked to the app via the ASGI standard — the async successor to WSGI. Embedded (Spring Boot): server (Tomcat) *bundled inside* the runnable app, auto-configured. Python keeps it explicit; Spring folds it in.

**Q: uvicorn vs gunicorn / true parallelism?**
uvicorn = one async worker (event loop → concurrency on one core). gunicorn = process manager running many uvicorn workers (one per core) → **true parallelism** (processes running simultaneously across cores), sidestepping the GIL. Stack: uvloop → uvicorn (concurrency/worker) → gunicorn (parallelism/workers).

---

## Dependency injection (FastAPI)

**Q: DI in FastAPI vs Spring?**
DI = objects *receive* dependencies instead of creating them (enables testing/swapping/central wiring). FastAPI: explicit, per-endpoint via `Depends()` — visible in the signature. Spring: automatic, container-managed bean graph wired at startup.

**Q: Why empty `Depends()` in the login route?**
Injects an instance of the *annotated type* (`OAuth2PasswordRequestForm`) — same as `Depends(OAuth2PasswordRequestForm)`, without repeating the name. For classes FastAPI builds from the request; your own functions (`get_db`) are named explicitly.

**Q: Where is `get_current_user` used?**
Defined once as a dependency; does nothing until attached via `user = Depends(get_current_user)` on an endpoint. Adding it protects the route (no valid token → 401). Dependencies chain — `rate_limit` itself `Depends(get_current_user)`.

**Q: uvicorn "could not import module main"?**
App path uses **dots**, not slashes, matching the file. `src/main.py` → `src.main:app`. Correct: `uv run uvicorn src.main:app --reload`.

---

## Data layer (Postgres, SQLAlchemy, Alembic)

**Q: Why engine + session + ORM for Postgres?**
Postgres is relational (tables/rows/SQL/transactions) → needs an **engine** (connection pool), **sessions** (units of work), an **ORM** (`Mapped`/`mapped_column` maps classes↔tables), and **migrations** (schema versioning). `get_db()` yields a session per request (context manager + generator + DI).

**Q: What is a migration?**
Version control for the DB *schema*. Numbered, reversible scripts (`upgrade`/`downgrade`). Alembic workflow: model → `revision --autogenerate` → review → `upgrade head` → verify.

**Q: "Target database is not up to date"?**
A generated-but-not-applied migration exists; Alembic won't stack a new one on it. Fix: `alembic upgrade head`. Two states: generated (file) vs applied (in DB).

**Q: "column user_id contains null values" (adding NOT NULL)?**
Existing rows can't satisfy a new NOT NULL rule. Dev: delete rows, re-migrate. **Prod pattern:** add nullable → backfill → alter to NOT NULL.

**Q: How do I view the DB table?**
`docker compose exec postgres psql -U app -d urlshortener` → `\dt`, `\d urls`, `SELECT * FROM urls;`. Or a GUI on `localhost:5432` (works because compose maps `5432:5432`).

---

## Auth & security

**Q: What is RBAC and how is it done?**
Authorization (what you can do) vs authentication (who you are). Users get roles; roles carry permissions. Flow: role on user → login embeds role in a **signed JWT** → each request verifies token + checks role → allow or 403. Signature prevents forgery.

**Q: When is `role` set?**
By the model's `default="user"`, automatically at creation — never in register code. Deliberate: registration only makes regular users; admins come from a controlled path (DB update / seed / admin-only endpoint).

**Q: Why does `POST /urls` return 401?**
Auth working — it requires `get_current_user`, hence an `Authorization: Bearer <token>` header. Login → get token → send it (Swagger "Authorize" / Postman Bearer).

**Q: Password storage?**
One-way **bcrypt** hash (via `bcrypt` directly — passlib is unmaintained and broke on new bcrypt). Hash on register, verify attempt on login. Never store/compare plaintext; bcrypt caps at 72 bytes.

**Q: What is a short code?**
The unique identifier (`abc123`) representing a long URL; `short_code → original_url` mapping stored in an indexed, unique column. POST mints it; GET looks it up and redirects.

**Q: Can I log in via Postman?**
Yes — login uses **form data** (`x-www-form-urlencoded`, keys `username`/`password`), not JSON, because of `OAuth2PasswordRequestForm`. Register uses raw JSON. Protected routes: Authorization tab → Bearer Token.

---

## Redis, caching, rate limiting

**Q: Redis needs no engine — how does it work internally?**
Redis = **in-memory key-value store** (a dict in RAM). No schema/SQL → just `.get()`/`.set()`, no engine/ORM. Fast because RAM + single-threaded microsecond hash-table ops. TTL auto-expires keys. Source of truth stays in Postgres; Redis is a disposable copy.

**Q: How does caching work here?**
**Cache-aside**: check Redis → hit? return (skip DB). Miss? query Postgres, `set` with a TTL, return. Proof: SQL runs on first hit, silent on repeats.

**Q: Cache invalidation on update/delete?**
After changing/removing the row in Postgres, `redis_client.delete(f"url:{code}")` so the next read repopulates from truth. Delete (not update) the key — simplest, avoids cache/DB drift. ("Cache invalidation" = one of CS's two hard problems.)

**Q: How does rate limiting work?**
Redis counter per user+window: `incr` the key; on first hit set `expire(window)`; if count > limit → **429**. Fixed-window algorithm. Implemented as a dependency that itself `Depends(get_current_user)`.

---

## API design & config

**Q: POST-ing the same URL creates a duplicate — bug?**
Design choice. **POST is not idempotent** — each call creates a new resource. Chosen: allow duplicates (good for per-link analytics). Alt: dedupe (check-then-return-existing). Concepts: **idempotency**, **upsert**, idempotency keys (how Stripe avoids double-charges).

**Q: Short-code "duplicate key violates unique constraint"?**
Random generator produced an existing code; `unique=True` correctly refused. Fix: generate → check → retry until unique. Lessons: random ≠ unique; bigger alphabet/longer codes reduce collisions; ID→base62 is collision-free by design. The constraint was a safety net.

**Q: Rate-limit values in code or config/env?**
Config. **Config vs code separation**: environment-varying/tunable values (limits, TTLs, flags) → `Settings` with defaults. Secrets (JWT key, DB pass) → `.env`. True constants (short-code alphabet) → code.

**Q: `.env` before committing?**
Yes — move secrets out *before* first commit or they live in git history forever. Real secrets in `.env` (gitignored); committed `.env.example` template; remove secret defaults in `config.py` so missing config fails loudly.

---

## Docker & infra

**Q: What is Docker?**
Packages an app + its whole environment into an isolated **container** running identically anywhere. **Image** = template (`postgres:16`); **container** = running instance; **docker-compose** = multiple containers from one file. Lighter than a VM (shares host kernel).

**Q: "permission denied ... docker.sock"?**
User not in `docker` group. `sudo usermod -aG docker $USER`, then `wsl --shutdown` (from Windows) to apply. Avoids `sudo` per command.

**Q: "no configuration file provided"?**
No `docker-compose.yml` in the current dir. Create it (exact name, YAML spaces), then `docker compose up -d`.

---

## Git & setup gotchas

**Q: "Password authentication is not supported"?**
GitHub dropped password auth. Use a **PAT** (paste as password / embed in URL) or **SSH** (`ssh-keygen`, add `.pub`, remote `git@github.com:user/repo.git`) — SSH never prompts again.

**Q: Gitignored files still committed?**
`.gitignore` is **not retroactive** — only ignores untracked files. Fix: `git rm -r --cached .` then `git add .` (`--cached` never deletes from disk).

**Q: SSH key "wrong format"?**
Copied the private key or broke the line. Public key = one line starting `ssh-ed25519`. `cat ~/.ssh/id_ed25519.pub | clip.exe`.

**Q: "LF will be replaced by CRLF"?**
Harmless line-ending warning (Linux LF vs Windows CRLF). Silence: `git config --global core.autocrlf false`.

**Q: "uv not found" in a `MINGW64` / `/c/Users/...` shell?**
That's **Git Bash (Windows)**, not **WSL Ubuntu** — different PATH/home/tools. uv installed in Ubuntu only exists there. Always work from a **WSL terminal** (VS Code `><` → Connect to WSL).

---

## Habits reinforced
Read errors **bottom-up**. **Commit** working code before the next step (a commit is a recovery point). Never commit `.env`. Dependency version conflicts (passlib vs bcrypt) are normal — patch or remove the broken layer. Review autogenerated migrations. Constraints (unique/NOT NULL) are safety nets.

## Parked
Slice E (RabbitMQ pub/sub — publish click events → consumer analytics). Slice F (dockerize app + tests). Phase 2: AWS-native deploy (ECS/Fargate, RDS, ElastiCache, Amazon MQ/SQS+SNS, ECR, Secrets Manager, Terraform/CDK) — after the local project is complete.