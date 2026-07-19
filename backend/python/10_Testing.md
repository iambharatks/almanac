# 10 · Testing

> ⭐ **The one idea:** DI makes code testable — override `Depends(get_db)` with a function reference and it's swapped everywhere it's used. A bare module-level global (`redis_client`) requires fragile string-based patching instead. **Same lesson, proved twice, two different outcomes.**

---

## 1. Where tests live and why

`tests/` as a **sibling** to `src/`, never inside it. `src/` is what gets packaged/containerized/deployed (the Dockerfile only copies `src/`); tests are dev tooling. Standard Python "src layout"; pytest discovers `tests/` automatically.

*Java: enforced by the build tool — `src/main/java` vs `src/test/java`, as separate trees.*

## 2. The stack

```bash
uv add --dev pytest pytest-asyncio httpx
```
- **pytest** — the framework
- **pytest-asyncio** — lets pytest run `async def` tests (needed since the app is async)
- **httpx** `AsyncClient` + `ASGITransport` — calls your FastAPI app **in-process**, no real network, no running server

```toml
[tool.pytest.ini_options]
asyncio_mode = "auto"
pythonpath = ["."]      # fixes ModuleNotFoundError: No module named 'src'
```

`pythonpath` is needed because `sys.path` is populated by **how you launch** — `uvicorn src.main:app` from the root puts the root on the path implicitly; pytest doesn't, until told to.

## 3. Fixtures — DI for tests

```python
@pytest_asyncio.fixture(autouse=True)
async def setup_database():
    async with engine_test.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)    # SETUP
    yield                                                  # ← the test runs here
    async with engine_test.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)       # TEARDOWN
```

- **`@pytest_asyncio.fixture`** — like `@pytest.fixture` but for `async def` (a coroutine can't just be called; pytest-asyncio drives it on an event loop).
- **`yield` pattern** — before-`yield` = setup, after-`yield` = teardown; same shape as `get_db()` and FastAPI's lifespan.
- **`autouse=True`** — runs for every test automatically, without being requested by name.
- Fixtures inject **by parameter name**: `def test_x(client, auth_headers)` → pytest resolves each and passes the result.

*Java: `@BeforeEach`/`@AfterEach` split into two methods; pytest combines both around one `yield`. Fixture-as-parameter ≈ `@Autowired`/`@MockBean`.*

## 4. `create_all`/`drop_all` vs Alembic in tests

`create_all` builds every table **directly from the models**, in one shot, no history — fast, and exactly what "fresh schema for this test" needs. Alembic builds **incrementally** with version history — needed in prod to preserve real data. `drop_all` after each test → **complete isolation**, no order dependencies.

⚠️ Trade-off: tests validate against the **models**, not the **migrations** — a migration out of sync with the models won't be caught this way.

## 5. Overriding the database — DI in action

```python
engine_test = create_async_engine(TEST_DATABASE_URL, poolclass=NullPool)   # see §7
async_session_test = async_sessionmaker(engine_test, expire_on_commit=False)

@pytest_asyncio.fixture
async def client():
    async def override_get_db():
        async with async_session_test() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db     # swap — app code untouched
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
    app.dependency_overrides.clear()
```

**Endpoints don't know or care** — they still say `Depends(get_db)`. This substitutability *is* the point of DI.

## 6. Engine vs session — no different in tests, only reconfigured

Same machinery as production; only the **URL differs** (`urlshortener_test`). `dependency_overrides` swaps which sessionmaker endpoints receive — proving DI's value directly.

## 7. Event loops in tests — the recurring failure mode

pytest-asyncio gives **a fresh event loop per test function** (`asyncio_default_test_loop_scope=function`) — deliberate, for isolation (same principle as `create_all`/`drop_all`, applied to async state instead of data). A resource created at **import time** and **pooled** (SQLAlchemy engine, Redis client) binds to whichever loop was running then; reused on a later test's *different* loop → `InterfaceError: another operation is in progress` or `RuntimeError: Event loop is closed`.

**Fixes:**
```python
engine_test = create_async_engine(TEST_DATABASE_URL, poolclass=NullPool)   # no reuse across loops
```
For Redis/RabbitMQ, either **mock them** or move creation to **lifespan + DI** (see doc 03/09) so they're born inside the running loop and closed cleanly.

*(Full mechanism — fd/loop affinity, why RabbitMQ survived by accident and Redis didn't — is in doc 03, §10.)*

## 8. Mocking external services

**If reached via a dependency** (the good case): override it exactly like the DB —
```python
app.dependency_overrides[get_redis] = lambda: mock_redis
```
Function reference, no strings, refactor-safe.

**If reached as a bare module global:**
```python
monkeypatch.setattr(urls_router, "redis_client", mock_redis)
```
Fragile — the string must match the **exact** name in that module's namespace; rename/move the module and it silently patches nothing, so the real client runs and the failure is confusing.

**`AsyncMock` return-value discipline:** a mock returns another mock by default — specify a return value for every method whose result the code actually *uses*:
```python
mock_redis.get.return_value = None      # cache miss → falls through to DB
mock_redis.incr.return_value = 1        # else: TypeError '>' between AsyncMock and int
```
Methods whose return value is ignored (`set`, `expire`) can stay default.

**Better alternatives, roughly in order of effort:**
1. **`fakeredis`** — in-memory real Redis behaviour; `incr` actually increments. One line, no hand-maintained returns, more faithful.
2. **Testcontainers** — tests programmatically start/destroy a real throwaway Postgres/Redis. Highest fidelity, isolated, CI works identically. *(Same idea as Java's Testcontainers — where it originated.)*
3. **Config** — set `rate_limit` absurdly high in tests so it never triggers (honest: not testing rate limiting there).

## 9. Does testing need Docker running?

Yes, for **integration** tests connecting to a real Postgres: `docker compose up -d postgres` (or the full stack). Tests run **on your machine** via `uv`, reaching containers through mapped ports (`localhost:5432`) — same pattern as running the app locally. Mocked services don't need to be up.

**Layered practice:** unit (all mocked, fast, constant) → integration (real DB, pre-commit/CI — what this project has) → e2e (full stack via compose).

## 10. What's actually worth testing

**Happy paths** (register, login, create, redirect) **+ failure paths** (duplicate email, wrong password, unknown code) **+ security boundaries** (401 without a token; `hashed_password` never in a response). Failure/boundary tests are often more valuable than happy paths — they're what actually catches bugs (the redirect-auth mismatch was found this way, not by manual clicking).

```python
async def test_create_url_requires_auth(client):
    response = await client.post("/urls", json={"original_url": "https://example.com"})
    assert response.status_code == 401

async def test_login_wrong_password_fails(client):
    ...
    response = await client.post("/auth/login", data={"username": ..., "password": "WRONG"})
    assert response.status_code == 401
```

Detail: `follow_redirects=False` to assert on the redirect itself; login uses `data=` (form) not `json=`.

---

## 🎤 Interview answers

**"How do you test a FastAPI app that talks to a database?"**
> Integration tests with `httpx.AsyncClient` against the app in-process, backed by a separate test database. A fixture creates all tables fresh before each test and drops them after, so tests are fully isolated — no shared state or ordering dependencies. The database dependency is overridden via `app.dependency_overrides`, so the application code under test is completely unaware it's talking to a test database.

**"Why did your Redis mocking fight you more than the database?"**
> The database was reached through a dependency, so the test could override it with a function reference — clean and refactor-safe. Redis was a bare module-level global, so I had to monkeypatch the exact attribute name in the module's namespace, which is fragile and broke twice when the actual import didn't match my assumption. It's a live argument for routing every external service through dependency injection, not just the database.

**"What's the value of writing failure-path tests, not just happy-path?"**
> They catch real bugs. My redirect endpoint had accidentally picked up an auth dependency — testing the 404 path surfaced a 401 instead, which told me unauthenticated users couldn't use the core feature at all. That's not something you'd necessarily notice testing manually while logged in.

---

## ✅ Gotcha checklist

- [ ] `tests/` sibling to `src/`, never inside it
- [ ] `pythonpath = ["."]` for pytest to find `src`
- [ ] `poolclass=NullPool` on the test engine — pooled connections cross event loops
- [ ] `create_all`/`drop_all` per test for isolation; won't catch a broken migration
- [ ] Override dependencies with function references, not string-based `patch()`, when possible
- [ ] Specify `AsyncMock` return values for every method whose result is actually used
- [ ] Test failure paths and security boundaries, not just happy paths
- [ ] Docker must be running for integration tests against a real Postgres
