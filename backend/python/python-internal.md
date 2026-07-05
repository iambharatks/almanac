# Python Internals — Notes
*Language & runtime deep-dives, as Q&A. Crisp, technical. Anchor: Java binds structure at compile time; Python resolves at runtime via dicts.*

---

## Object model & attribute access

**Q: What happens on `self.name = "Rex"`?**
It's `self.__dict__['name'] = "Rex"`. An instance is essentially a `dict`; attributes are entries → addable/removable at runtime (vs Java's fixed compile-time fields).

**Q: Attribute lookup order for `obj.x`?**
data descriptors (class) → instance `__dict__` → class `__dict__` + parents (MRO) → non-data descriptors / class attrs → `__getattr__` / `AttributeError`. This single order explains descriptors, shadowing, properties.

**Q: Class vs instance attribute — the `a.count += 1` trap?**
Reads the class attribute but **writes an instance attribute** that shadows it. `a` changes; `b`/class don't. Mutable class attrs (shared list) mutated via an instance affect all instances — classic bug.

**Q: Why do SQLAlchemy columns use no decorators?**
`Mapped[str] = mapped_column(...)` is **annotation + assignment**, backed by descriptors. Decorators transform *functions*; columns declare *typed data attributes*. (Routes use `@app.get` because they wrap functions.)

---

## Classes, `type`, inheritance

**Q: Explain `type`.**
Two jobs: `type(x)` = an object's class; and `type` is the **metaclass** — the class that builds classes (`type(Dog)` is `type`). `class` is sugar for `type(name, bases, namespace)`. Classes are runtime objects (Java's are compile-time only).

**Q: Is there a fixed MRO order or do I look it up?**
Deterministic — **C3 linearization** (child before parents; parents in listed order; shared ancestor delayed until after its children; each once). Guarantees a unique order or refuses the class. Confirm via `Class.__mro__`.

**Q: What does `super()` mean?**
"Next class in the MRO", not "my parent". With multiple inheritance it can call a sibling. Cooperative dispatch — check `__mro__` when surprised.

---

## Descriptors & the `@property` family

**Q: What are data descriptors?**
Objects defining `__get__`/`__set__`/`__delete__`, stored as class attributes, that intercept attribute access. **Data** = defines `__set__` (wins over instance `__dict__`). **Non-data** = only `__get__` (loses to instance `__dict__`). Per-instance state goes in `obj.__dict__` (the descriptor is shared).

**Q: Descriptors vs Java?**
No equivalent. Java getters/setters weld logic to one field; a descriptor is a *reusable object* attachable to any attribute — only possible because of Python's runtime dict-lookup model. `@property`, methods, `classmethod`/`staticmethod`, ORM columns are all descriptors.

**Q: `@property` + `@x.setter` — why write the setter if it "already has" set?**
Getter-only `@property` is **read-only** (assigning raises). `@x.setter` fills the setter slot. A `property` holds getter/setter/deleter slots; you fill only what you want. Storage uses `_x` to avoid setter recursion.

**Q: Is a getter-only property data or non-data?**
**Data** — `property` *always defines* `__set__`; with no setter it just raises `AttributeError`. Category = "is `__set__` defined?", not "is it useful?". That's why read-only properties can't be silently shadowed by an instance attr (the `AttributeError` on assignment *is* `__set__` running).

**Q: `@classmethod` vs `@staticmethod`?**
`classmethod` gets `cls` → alternative constructors (`cls(...)` is subclass-correct). `staticmethod` gets nothing → grouped helper needing no state. Normal method gets `self`. `staticmethod` switches off the function's `self`-binding descriptor behavior.

---

## Memory model

**Q: Complex memory model like Java?**
Different in kind. No primitives — **everything is a heap object**. Variables are **names bound to objects** (references), so `a = b` aliases, not copies. Freed by **reference counting + cyclic GC** (for reference cycles). Small ints (−5..256) and some strings **interned**. `is` = identity, `==` = value (`__eq__`).

**Q: How is `__slots__` more optimal?**
Removes the per-instance `__dict__`, storing attrs in fixed slots (like Java fields at fixed offsets) → big memory savings + faster access. Only worth it for *many* instances of a simple class. Trade-off: no dynamic attributes.

---

## Concurrency & the GIL

**Q: What is the GIL?**
Global Interpreter Lock — only one thread executes Python bytecode at a time per process. So threads give **concurrency** (I/O juggling) but not multi-core CPU **parallelism** within one process. Multi-core needs multiple processes.

**Q: Concurrency vs parallelism?**
Concurrency = one worker switching between tasks fast (one runs per instant) — the event loop. Parallelism = multiple workers running simultaneously on different cores. One chef juggling vs many chefs cooking.

**Q: What is the async event loop / uvloop?**
A single thread that starts many I/O ops and parks whichever is *waiting* (`await`), resuming it when ready — non-blocking I/O. **uvloop** replaces asyncio's slow pure-Python loop with a Cython/libuv one (the engine Node uses) → ~2–4× faster. Camps: explicit event loop (Python-async, Node) vs runtime-hidden lightweight threads (Go goroutines, Java virtual threads — true multi-core built in).

---

## Bytecode & tooling

**Q: What is `__pycache__`?**
Python compiles imported modules to **bytecode** (`.pyc`) cached here, so imports skip recompiling next run. Version-tagged (`cpython-312`) since bytecode isn't cross-version. Generated artifact → gitignore, never edit, safe to delete. ~ Java's `.class` files.

**Q: uv vs pip; Python version to use?**
pip installs packages; **uv** (Rust) also manages venvs, Python versions, lockfiles — 10–100× faster (`uv add`, `uv run`). Use **Python 3.12** (widest lib compatibility); 3.14 is too new (library lag). Java prod uses LTS (21); Python has ~5-yr support windows, no formal LTS.

---

## The through-line
Java decides structure & dispatch at compile time (fixed, fast, rigid). Python resolves everything at runtime via dict lookups (flexible, dynamic, dispatch-by-search). Object model, MRO, descriptors, and `type` are all faces of that one idea.