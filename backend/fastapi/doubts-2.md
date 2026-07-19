# Doubts & Answers — FastAPI Backend Build (Reference)
*Consolidated conceptual questions asked during the build, with concise answers. Doubles as interview revision.*

---

## Python & OOP internals

**Q: Is a getter-only `@property` a data or non-data descriptor?**
A **data descriptor** — even without a setter. `property` *always defines* `__set__`; with no setter that `__set__` just raises `AttributeError`. Data-vs-non-data is decided by whether `__set__` is *defined*, not whether it does anything useful. This is why a read-only property can't be silently shadowed by an instance attribute — it wins over the instance `__dict__`.

**Q: In `@property` + `@x.setter`, what is `x.setter` — and why create it if the property "already has" set?**
A getter-only `@property` is **read-only by default** (assigning raises). `@x.setter` *adds* the ability to write by attaching a setter function to the same property object. You create it explicitly because read-only is a deliberate, common choice (e.g. computed values like `area`). A `property` holds up to three slots — getter/setter/deleter — and you fill only the ones you want.

**Q: `@classmethod` vs `@staticmethod`?**
`@classmethod` receives `cls` (the class) → used for **alternative constructors** (`cls(...)` respects subclasses). `@staticmethod` receives nothing → a **grouped helper** that needs no instance/class state. Normal method receives `self`.

**Q: Why no decorators on SQLAlchemy model columns?**
Columns use **type annotation + assignment** (`Mapped[str] = mapped_column(...)`), not decorators. Decorators wrap/transform *functions*; columns declare *typed data attributes*, backed by the descriptor protocol. (FastAPI routes DO use decorators — `@app.get(...)` — because they wrap handler functions.)

**Q: Is there a specific order for the MRO, or do I look it up?**
There's a deterministic algorithm — **C3 linearization**. Rules: child before parents; parents in listed order; shared ancestor delayed until after all its children; each class once. It guarantees a unique valid order or refuses to create the class. Always confirmable via `Class.__mro__`.

**Q: What are data descriptors?**
Objects defining `__get__`/`__set__`/`__delete__`, stored as class attributes, that intercept attribute access. **Data descriptor** = defines `__set__` (wins over instance dict). **Non-data** = only `__get__` (loses to instance dict). `@property`, methods, and ORM fields are all descriptors.

**Q: Explain `type` in detail.**
Two jobs: (1) `type(x)` returns an object's class; (2) `type` is the **metaclass** — the class that builds all classes (`type(Dog)` is `type`). `class` is sugar for `type(name, bases, namespace)`. Classes are runtime objects you can build/inspect — unlike Java's compile-time classes.

**Q: Is Python's memory model complex like Java's?**
Different in kind. Python: everything is a heap object (no primitives), variables are names→object references, memory freed by **reference counting + cyclic GC**, small ints/strings interned, and the **GIL serializes bytecode** (so no formal concurrency memory model like Java's JMM). Java: primitives vs objects, stack vs heap, tracing GC, formal JMM.

**Q: How is `__slots__` more optimal?**
It removes the per-instance `__dict__`, storing attributes in fixed slots (like Java fields at fixed offsets). Saves significant memory and speeds attribute access — worth it only when you create *many* instances of a simple class. Trade-off: no dynamic attributes.

---

## Concurrency & servers

**Q: What is uvloop and how does it compare to Java/Go/Node?**
uvloop replaces asyncio's default (slow, pure-Python) event loop with a fast Cython/libuv one — the same engine Node uses — making Python async ~2–4× faster. **Two camps:** explicit event loop (Python-async, Node — you write `await`, single-threaded, GIL/no-multicore-in-one-process) vs invisible lightweight threading (Go goroutines, Java virtual threads — write blocking code, runtime hides async, true multi-core parallelism built in).

**Q: What is gunicorn? What is "true parallelism"?**
A single Python process = one core (GIL). **True parallelism** = multiple processes running simultaneously on multiple cores (genuinely at the same instant, not taking turns). gunicorn runs *many* worker processes (one per core) and distributes requests, so all cores are used — sidestepping the GIL. Full stack: **uvloop** (fast loop) → **uvicorn** (one async worker, concurrency per core) → **gunicorn** (many workers, parallelism across cores).

**Q: Concurrency vs parallelism?**
Concurrency = one worker switching between tasks fast (only one runs at any instant) — the event loop. Parallelism = multiple workers running literally at the same instant on different cores. One chef juggling dishes vs many chefs cooking at once.

**Q: What is an embedded server vs an ASGI server?**
ASGI server (Python): a *separate, swappable* program (uvicorn) connected to your app via the ASGI standard contract. Embedded server (Spring Boot): the server (Tomcat) is *bundled inside* the runnable app. Python keeps the server separate and explicit; Spring folds it in and auto-configures it.

---

## FastAPI & dependency injection

**Q: What is DI, and how does FastAPI's differ from Spring's?**
DI = objects *receive* dependencies instead of creating them (enables testing, swapping, central wiring). FastAPI: explicit, per-endpoint via `Depends()` — you see it in the signature. Spring: automatic, container-managed — the whole app is a bean graph wired at startup.

**Q: Why empty `Depends()` in the login route?**
`Depends()` with no argument tells FastAPI to inject an instance of the *annotated type* (`OAuth2PasswordRequestForm`). Equivalent to `Depends(OAuth2PasswordRequestForm)` — the empty form just avoids repeating the class name. Used for classes FastAPI builds from the request; named functions (`get_db`) are passed explicitly.

**Q: Where is `get_current_user` used?**
It's a *guard* you define once, then attach to endpoints via `user = Depends(get_current_user)`. Until added to an endpoint's signature it does nothing — like a bouncer you built but haven't stationed at a door. Adding it protects the endpoint (no valid token → 401).

---

## Auth & security

**Q: When is `role` defined on a user?**
By the model's `default="user"` — automatically applied at creation, *never* set in the register code. This is deliberate: registration always creates regular users, so nobody can self-register as admin. Admins are created via a controlled path (DB update, seed script, or an admin-only endpoint).

**Q: Why does `POST /urls` return 401?**
That's auth *working*. The endpoint now requires `get_current_user`, so it demands a valid `Authorization: Bearer <token>` header. Log in → get token → in Swagger click "Authorize" and paste it (or use the login form) → the token is attached to requests → endpoint works. 401 without a token = the security boundary enforcing itself.

**Q: What is a short code?**
The unique little identifier (`abc123`) representing a long URL. `POST /urls` generates it and stores the `short_code → original_url` mapping; `GET /{short_code}` looks it up and redirects. In the DB it's just a unique, indexed column.

---

## Databases, migrations & tooling

**Q: What is a migration?**
Version control for your database *schema*. Each migration is a numbered, reversible script (`upgrade`/`downgrade`) describing one schema change. Tools: Alembic (Python), Flyway/Liquibase (Java). Workflow: model → autogenerate → review → apply → verify.

**Q: "Target database is not up to date" error?**
Means you have a *generated-but-not-applied* migration. Alembic refuses to generate a new one on top of a pending one. Fix: apply the pending one first with `alembic upgrade head`. Migrations have two states — generated (file exists) and applied (run against DB).

**Q: "column user_id contains null values" when adding a NOT NULL column?**
Existing rows can't satisfy a new NOT NULL rule. Dev fix: delete the old rows, re-migrate. **Production pattern:** add column as nullable → backfill existing rows with values → alter to NOT NULL in a follow-up migration. (Add nullable → backfill → enforce.)

**Q: Difference between uv and pip?**
pip only installs packages. uv is one tool that installs packages *plus* manages virtual environments, Python versions, and lockfiles — and is 10–100× faster (Rust). `uv add X` (not `pip install`), `uv run cmd` (runs in the env automatically, no manual activate).

**Q: What is Docker? / explain it.**
Packages an app with its entire environment into an isolated **container** that runs identically anywhere ("works on my machine" → "works everywhere"). **Image** = template (e.g. `postgres:16`); **container** = running instance of an image; **docker-compose** = defines/runs multiple containers together from one file. Lighter than a VM (shares the host kernel, no full guest OS).

**Q: What is `__pycache__`?**
Python's automatic cache of compiled **bytecode** (`.pyc`) for imported modules, so imports start faster. A generated artifact — gitignore it, never commit or edit it, safe to delete anytime. Rough equivalent of Java's `.class` files.

**Q: Should env values live in a settings file or `.env`?**
`.env` — secrets (JWT secret, DB password) must NOT be hardcoded in code that goes to git (they'd live in history forever). Pattern: real secrets in `.env` (gitignored), a committed `.env.example` template showing what variables exist. Force secrets from env by removing their defaults in `config.py`.

---

## Git (hard-won lessons)

**Q: "Password authentication is not supported" pushing to GitHub?**
GitHub removed password auth for git. Use either a **Personal Access Token** (paste as the password, or embed once in the remote URL) or **SSH keys** (`ssh-keygen`, add the `.pub` to GitHub, set the remote to `git@github.com:user/repo.git` — never asks for a password again).

**Q: Files in `.gitignore` still got committed — why?**
`.gitignore` is **not retroactive** — it only stops tracking files git *isn't already tracking*. If a file was added before being ignored, git keeps it. Fix: `git rm -r --cached .` then `git add .` (untracks everything, re-adds only non-ignored files — `--cached` never deletes from disk).

**Q: "LF will be replaced by CRLF" warnings on `git add`?**
Harmless line-ending warnings (Linux uses LF, Windows CRLF). Doesn't change or break code. Silence with `git config --global core.autocrlf false`.

**Q: SSH key "wrong format" on GitHub?**
Almost always: you copied the *private* key instead of the `.pub` (public) one, or the paste broke across lines. The public key is one line starting with `ssh-ed25519`. Copy cleanly with `cat ~/.ssh/id_ed25519.pub | clip.exe`.

---

## Key habit reminders

- **Read errors bottom-up** — the last line is the error type; the line above points to *your* code.
- **Commit working code before building the next thing** — a commit is a save and a recovery point.
- **Never commit `.env`** — check `git ls-files | grep "\.env"` before pushing.
- **Dependency version conflicts (e.g. passlib vs bcrypt) are normal** — diagnose which two libraries disagree, then patch (pin a version) or fix properly (remove the broken dependency).