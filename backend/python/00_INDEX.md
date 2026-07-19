# Revision Notes — Index ✅ complete set (00–11)

Section-wise notes from building the FastAPI URL shortener (Slices A–F).
**Python primary · Java contrast throughout.** Each doc is scannable in ~5 minutes.

---

## The documents

### Python
| # | Doc | Covers |
|---|---|---|
| 01 | **Python Object Model & OOP** | `__dict__`, attribute lookup, `type`/MRO, descriptors, `@property`, access specifiers, dataclasses |
| 02 | **Python Memory & Internals** | heap objects, refcounting + cyclic GC, interning, `__slots__`, bytecode, tooling |
| 03 | **Python Concurrency & Async** | sync vs async, coroutines, GIL, event loop, sockets/fds, loop affinity, context managers |

### Backend / FastAPI
| # | Doc | Covers |
|---|---|---|
| 04 | **FastAPI & the Web Layer** | request lifecycle, ASGI/uvicorn/gunicorn, DI (`Depends`), Pydantic, framework comparison |
| 05 | **Data Layer** | engine vs session, SQLAlchemy 2.0, Alembic migrations, indexing, transactions |
| 06 | **Auth & Security** | bcrypt hashing, JWT, `get_current_user`, RBAC, ownership, secrets |
| 07 | **Caching & Redis** | Redis internals, cache-aside, all 5 strategies, invalidation, eviction, rate limiting |
| 08 | **Message Brokers** | RabbitMQ, exchanges/queues, pub/sub, acks, delivery guarantees, vs Kafka |
| 09 | **Docker, Config & Deployment** | images/containers, compose, Dockerfile, secrets, prod patterns, AWS mapping |
| 10 | **Testing** | pytest, fixtures, DI overrides, mocking, test DB, Testcontainers |
| 11 | **Troubleshooting Quick Reference** | every error hit, cause → fix, in one table |

---

## How to revise (suggested)

**Fast pass (30 min):** read the "⭐ The one idea" box + the tables in each doc. That's the skeleton.

**Interview prep:** each doc ends with **"Interview answers"** — canned 2–4 sentence responses to the questions actually asked. Practise saying them aloud.

**Deep pass:** work through the Q&A bodies; try to answer before reading.

**Weakness check:** each doc ends with a **gotcha checklist**. Anything you can't explain → reread that section.

---

## The project these came from

**URL shortener** — FastAPI + Postgres (async SQLAlchemy + Alembic) + Redis (cache + rate limit) + RabbitMQ (click analytics via pub/sub), fully containerized, with a pytest suite.

| Slice | Built | Key concepts |
|---|---|---|
| A | app + `/health` | ASGI, uvicorn, routing |
| B | create + redirect, Postgres | ORM, async DB, migrations, indexing |
| C | register/login, JWT | hashing, tokens, DI guards, RBAC |
| D | Redis cache + rate limit | cache-aside, invalidation, counters |
| E | RabbitMQ click events | pub/sub, exchanges, consumers, acks |
| F | Dockerize + tests | images, compose, secrets, pytest |

---

## Known tech debt (deliberate, logged)

- `extra="ignore"` removed → **resolved** via component-based settings + `@property` URLs
- Redis/RabbitMQ still module globals → **should** move to lifespan + DI (testability, clean shutdown)
- Mocks use hand-set `return_value` → **could** use `fakeredis`
- Integration tests need a manually-started Postgres → **could** use Testcontainers

## Next up

Consumer as a compose service · AWS phase (ECS/Fargate, RDS, ElastiCache, Amazon MQ, Secrets Manager, Terraform) · remaining OOP todos (ABCs, enums, inheritance/polymorphism, `__eq__`/`__hash__`, iterators)
