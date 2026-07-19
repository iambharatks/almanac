# 04 · FastAPI & the Web Layer

> ⭐ **The one idea:** Each tool does exactly **one job** and hands off to the next. uvicorn speaks HTTP, FastAPI routes and injects, Pydantic validates, your code decides, SQLAlchemy persists. Once you see the handoffs, the stack stops being intimidating.

---

## 1. What a backend is

A program that runs continuously waiting for **HTTP requests** — parses them, does work (DB reads, permission checks, logic), returns an **HTTP response** (JSON + a status code). That request → work → response cycle, millions of times, *is* a backend.

## 2. The request lifecycle

```
Browser
  ↓ HTTP request
uvicorn          — ASGI server: parses bytes → structured request, calls your app
  ↓
FastAPI          — routing: maps GET /users/5 → your function; resolves Depends()
  ↓
Pydantic         — validates the incoming data (bad data → automatic 422)
  ↓
router → service → repository      — your business logic
  ↓
SQLAlchemy + asyncpg → PostgreSQL  — permanent data
Redis                              — fast cached data
RabbitMQ                           — deferred event work
  ↓
Response (JSON + status) back out
```

Alembic built the schema · JWT proved who's asking · Docker runs all of it.

## 3. The pieces, one line each

| Piece | Job |
|---|---|
| **uvicorn** | ASGI server — speaks HTTP/networking so your code doesn't have to |
| **ASGI** | the *standard contract* between server and app (async successor to WSGI) |
| **uvloop** | the fast event loop bundled with `uvicorn[standard]` |
| **gunicorn** | process manager — many uvicorn workers, one per core |
| **FastAPI** | routing, dependency injection, auto-docs (`/docs`) |
| **Pydantic** | runtime validation from type hints; controls what goes out |
| **SQLAlchemy** | ORM — Python objects ↔ DB tables |
| **asyncpg** | the async driver that speaks Postgres' wire protocol |
| **Alembic** | version control for the DB schema |

**Chain of translation:** your code → SQLAlchemy (objects → SQL) → asyncpg (SQL → wire protocol) → PostgreSQL.

## 4. ASGI server vs embedded server

| | Python (ASGI) | Spring Boot (embedded) |
|---|---|---|
| Server | **separate, swappable** program (uvicorn) | **bundled inside** the app (Tomcat) |
| Contract | the ASGI standard | framework-internal |
| Feel | explicit — you run it | auto-configured — it just starts |

## 5. Framework comparison

| | Node.js | Spring Boot | FastAPI |
|---|---|---|---|
| Runtime | libuv event loop | JVM, thread-per-request / virtual threads | async event loop |
| Weight | light | heavy platform | light |
| Style | unopinionated → structured | **opinionated** | middle |
| Validation | manual/libs | annotations + beans | **type hints → runtime validation** |
| Sweet spot | real-time, full-stack JS | enterprise, fintech, scale | AI-adjacent, fast iteration |

**"Opinionated"** = the framework makes structural decisions for you (conventions, "the right way") → consistency, less to decide, steeper curve. Unopinionated = it hands you tools, you decide. *Analogy: package holiday vs backpacking with a map.* FastAPI is opinionated only about type hints/validation.

## 6. Dependency injection

**DI = objects *receive* their dependencies instead of creating them** → testable, swappable, centrally wired.

```python
async def create_url(
    payload: UrlCreate,                              # Pydantic validates
    db: AsyncSession = Depends(get_db),              # DI: session injected
    user: User = Depends(rate_limit),                # DI: auth + rate limit
):
```

| | FastAPI | Spring |
|---|---|---|
| Style | **explicit, per-endpoint** (`Depends()`) | automatic, container-managed |
| Visibility | visible in the signature | annotations + bean graph |
| Wiring | per-route | whole app at startup |

**Dependencies chain:** `rate_limit` itself does `Depends(get_current_user)` — FastAPI resolves the whole chain, so swapping `Depends(get_current_user)` → `Depends(rate_limit)` adds rate limiting *and* keeps auth in one line.

**Empty `Depends()`** — `form: OAuth2PasswordRequestForm = Depends()` injects an instance of the **annotated type** (same as `Depends(OAuth2PasswordRequestForm)`, without repeating the name). Used for classes FastAPI builds *from the request*; your own functions (`get_db`) are named explicitly.

> 💡 **Why DI actually matters** (proved in testing): the DB tested cleanly because endpoints reach it via `Depends(get_db)` → `app.dependency_overrides[get_db] = ...` swaps it in one line. Redis fought back because it was a bare module global → fragile string-based patching. **Same lesson, two outcomes.**

## 7. Pydantic

- Type hints **become runtime validation**. Wrong type / missing required field → automatic **422** before your code runs.
- Separate **schemas** (API shapes) from **models** (DB rows) — controls what goes *out* so you never leak `hashed_password`.
- `ConfigDict(from_attributes=True)` → build a response directly from a SQLAlchemy object.
- `pydantic-settings` `BaseSettings` → typed config from env/`.env`.
- v2 is Rust-cored (up to ~50× faster than v1). API changes: `.dict()`→`.model_dump()`, `@validator`→`@field_validator`.

**Still to learn:** `Field()` constraints, `@field_validator`/`@model_validator`, computed fields, serialization options (`exclude`, `by_alias`), aliases, custom types.

## 8. Routing details

- `@app.get("/health")` **is** a decorator (it wraps a handler function) — unlike ORM columns, which are annotation+assignment.
- `include_router(...)` mounts a router; `APIRouter(prefix="/auth", tags=["auth"])` groups routes.
- ⚠️ **Catch-all routes shadow specific ones.** `GET /{short_code}` matches *any* single segment — register it **last**, after `/urls`, `/auth/...`.
- Path params (`/{short_code}`), query params, and body are all extracted and validated automatically from the signature.
- `/docs` (Swagger) is generated free from your type hints; the **Authorize** button attaches the Bearer token to requests.

## 9. Project structure (the standard)

```
src/
├── main.py          # app, lifespan, router registration
├── config.py        # pydantic-settings
├── database.py      # engine, sessionmaker, Base, get_db
├── models.py        # SQLAlchemy tables
├── auth/            # router, schemas, service, security, dependencies
├── urls/            # router, schemas, service, repository
└── events/          # broker, consumer
tests/               # sibling to src — NOT inside it
```

**Layering:** router (HTTP only) → service (business logic) → repository (DB queries). Schemas ≠ models.

---

## 🎤 Interview answers

**"Walk me through what happens when a request hits your API."**
> uvicorn accepts the connection and parses HTTP into an ASGI message, then calls the app. FastAPI matches the route and resolves its dependencies — the DB session, the current user from the JWT, the rate-limit check. Pydantic validates the request body against the schema, returning 422 automatically if it's malformed. My handler runs the logic, talking to Postgres through async SQLAlchemy, Redis for cache, and publishing events to RabbitMQ. The return value is serialised by the response model and sent back.

**"Why FastAPI over Flask/Django?"**
> Native async, so it fits I/O-bound workloads where you're mostly waiting on databases and external services. Type hints double as runtime validation via Pydantic, which removes a lot of manual checking and generates interactive docs for free. It's lightweight and unopinionated about structure, which suits smaller services — Django would win when you want batteries included like an admin and built-in auth.

**"How does dependency injection work in FastAPI?"**
> You declare dependencies as parameters with `Depends()`. FastAPI resolves them before calling your handler, including chains — my rate limiter depends on the current-user dependency, which depends on the token. The practical payoff is testing: `app.dependency_overrides` swaps any dependency for a fake in one line, so I could point the whole app at a test database without touching application code.

---

## ✅ Gotcha checklist

- [ ] `src.main:app` — dots not slashes, matching the file location
- [ ] `FastAPI(title=...)` — keyword, not positional
- [ ] Catch-all routes must be registered **last**
- [ ] Schemas (API) separate from models (DB) — never return the ORM object raw with secrets on it
- [ ] Empty `Depends()` = inject the annotated type
- [ ] `--host 0.0.0.0` when running inside a container
- [ ] Reach external clients via dependencies, not module globals (testability)
