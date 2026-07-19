# Build Kickoff — FastAPI Project (auth + pub/sub + Docker)
*Decisions locked to 2026 industry standard. Goal: environment + first endpoint running TODAY. You write the features; this clears the setup friction.*

---

## The stack — locked (current standard, April 2026)

| Layer | Choice | Why this (not looping) |
|---|---|---|
| Python | **3.12** | Widest library compatibility; 3.13 fine too |
| Package/env mgr | **uv** (Astral) | The 2026 standard — 10–100× faster than pip; replaces pip+venv+poetry |
| Framework | **FastAPI** (0.136+) | De facto standard for Python APIs |
| Server | **uvicorn[standard]** | Gold-standard ASGI; `[standard]` gives uvloop+httptools (~2× I/O) |
| Validation | **Pydantic v2** + **pydantic-settings** | Type-hint validation; up to 50× faster than v1 |
| ORM | **SQLAlchemy 2.0 async** (`AsyncSession`) | Native async, pairs with FastAPI's event loop |
| DB driver | **asyncpg** | Async Postgres driver |
| Database | **PostgreSQL** | Default relational choice |
| Migrations | **Alembic** (day one) | Versioned, reversible schema — never skip |
| Cache | **Redis** | Cache-aside + rate limiting |
| **Broker (pub/sub)** | **RabbitMQ** via **aio-pika** | See decision below |
| Auth | **OAuth2 + JWT** (`pyjwt` + `passlib[bcrypt]` + `python-multipart`) | Industry-standard auth |
| Testing | **pytest** + **pytest-asyncio** + **httpx** | Standard async test stack |
| Lint/format/types | **Ruff** + **mypy** | Ruff replaces black+isort+flake8 |
| Container | **Docker** + **docker-compose** | Local multi-service stack |

**Architecture pattern:** layered — **router → service → repository**. Routers stay thin (HTTP only), services hold business logic, repositories hold DB queries. Pydantic **schemas** (API shapes) kept separate from SQLAlchemy **models** (DB rows). This is the widely-cited 2026 standard (zhanymkanov / Netflix-Dispatch style).

---

## The broker decision (you asked for pub/sub — here's the right call)

**Use RabbitMQ (via `aio-pika`) for this project.** Reasoning:

- It's a **true dedicated message broker** (unlike Redis Pub/Sub, which is fire-and-forget with no durability) — so you learn real broker concepts: exchanges, queues, routing, acknowledgments, consumers.
- It does **pub/sub cleanly** via a **fanout/topic exchange** (each subscriber queue gets its own copy — genuine pub/sub).
- It's **genuinely industry-standard** and **far lighter to run and learn than Kafka** — runs as one container in compose.
- The concepts transfer to any broker.

**Know the landscape (for interviews):**
- **Kafka** is the industry standard for *high-throughput event streaming* / event-driven at scale (retained log, consumer groups, replay). It's the thing to graduate to and be able to discuss — but operationally heavy and **overkill for a first project**.
- **Redis Streams** is a lightweight Kafka-lite if you ever want to avoid running a second service.

Right sequencing: **build on RabbitMQ now** (learn broker fundamentals), be able to *explain* when you'd reach for Kafka. That's the defensible, standard choice.

---

## Project structure (domain-driven, the standard)

```
urlshortener/
├── docker-compose.yml
├── pyproject.toml
├── .env
├── alembic/                 # migrations
├── src/
│   ├── main.py              # app entry: create app, register routers, lifespan
│   ├── config.py            # pydantic-settings (typed config)
│   ├── database.py          # async engine + session
│   ├── auth/
│   │   ├── router.py        # endpoints
│   │   ├── schemas.py       # pydantic (API)
│   │   ├── models.py        # sqlalchemy (DB)
│   │   ├── service.py       # business logic
│   │   ├── dependencies.py  # get_current_user etc.
│   │   └── security.py      # JWT + password hashing
│   ├── urls/                # core url-shortener domain
│   │   ├── router.py
│   │   ├── schemas.py
│   │   ├── models.py
│   │   ├── service.py
│   │   └── repository.py
│   └── events/              # pub/sub
│       ├── broker.py        # RabbitMQ connection
│       ├── publisher.py     # publish click events
│       └── consumer.py      # consume → process analytics
└── tests/
```

Don't create it all at once — grow it slice by slice (below).

---

## ▶️ TODAY — get the environment + first endpoint running

**1. Install uv** (one time):
```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

**2. Create the project pinned to Python 3.12:**
```bash
mkdir urlshortener && cd urlshortener
uv init --python 3.12
uv add "fastapi>=0.136" "uvicorn[standard]" "pydantic-settings" \
       "sqlalchemy>=2.0" "asyncpg" "alembic" "redis" "aio-pika" \
       "pyjwt" "passlib[bcrypt]" "python-multipart"
uv add --dev pytest pytest-asyncio httpx ruff mypy
```

**3. `docker-compose.yml`** (Postgres + Redis + RabbitMQ — your whole local stack):
```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: app
      POSTGRES_PASSWORD: app
      POSTGRES_DB: urlshortener
    ports: ["5432:5432"]
    volumes: ["pgdata:/var/lib/postgresql/data"]

  redis:
    image: redis:7
    ports: ["6379:6379"]

  rabbitmq:
    image: rabbitmq:3-management     # 'management' = web UI at :15672
    ports: ["5672:5672", "15672:15672"]
    environment:
      RABBITMQ_DEFAULT_USER: app
      RABBITMQ_DEFAULT_PASS: app

volumes:
  pgdata:
```

**4. Bring the infra up:**
```bash
docker compose up -d
```
Then open the RabbitMQ UI at http://localhost:15672 (app/app) — seeing it run kills a lot of the FOMO on its own.

**5. `src/main.py`** — your first endpoint:
```python
from fastapi import FastAPI

app = FastAPI(title="URL Shortener")

@app.get("/health")
async def health():
    return {"status": "ok"}
```

**6. Run it:**
```bash
uv run uvicorn src.main:app --reload
```
Open http://localhost:8000/health → `{"status":"ok"}`, and http://localhost:8000/docs → free Swagger UI.

**That's today's win:** full infra (DB + cache + broker) running in Docker, plus a live API with auto-docs. Real, running, yours.

---

## The build slices (what you write next — this is the real experience)

| Slice | You build | Concepts earned |
|---|---|---|
| **A** ✅ today | health endpoint + infra up | FastAPI, uvicorn, docker-compose |
| **B** | `urls` domain: create short URL + redirect, Postgres via SQLAlchemy async + Alembic migration | ORM, async DB, migrations, router/service/repo |
| **C** | `auth`: register/login → JWT, `get_current_user` dependency, RBAC (owner/admin) | OAuth2, JWT, hashing, DI, authz |
| **D** | Redis cache-aside on redirects + rate limiting | caching, invalidation, TTL |
| **E** | `events`: publish click events to RabbitMQ (fanout) → consumer processes analytics | **pub/sub**, exchanges, queues, acks, async workers |
| **F** | Dockerize the app itself + deploy (AWS/Fly/Render) + tests | containers, deployment, pytest |

Each slice = one honest resume-able skill. Write them yourself — that's what makes them defensible.

---

## Best resources (curated, authoritative — don't drown in tutorials)

- **FastAPI official docs** — Tutorial + Advanced User Guide (fastapi.tiangolo.com). Your primary spine.
- **zhanymkanov/fastapi-best-practices** (GitHub) — the widely-cited conventions/structure reference.
- **benavlabs/FastAPI-boilerplate** (GitHub) — a clean async FastAPI + Pydantic v2 + SQLAlchemy 2.0 + Postgres + Redis reference to *read against your own build* (not clone-and-claim).
- **Pydantic v2 docs** + **SQLAlchemy 2.0 (async) docs** — reference as you hit them.
- **aio-pika docs** — RabbitMQ async patterns (Slice E).
- **TestDriven.io** — "FastAPI + Postgres + Docker" course — mirrors this project closely.

Rule: read a resource **when a slice needs it**, not front-to-back. The docs are reference, the build is the teacher.

---

## The honest note

This file removes the **setup friction** (environment, versions, infra, structure) — the part that causes FOMO and decision-loops. It deliberately does **not** write the features for you: auth, the pub/sub logic, the caching — those you write yourself, because code you wrote is the only code you can defend in an interview. Run Slice A today; the rest is yours to build.