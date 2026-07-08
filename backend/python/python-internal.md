# Python Internals & OOP — Notes
*Curriculum-structured. Python is primary; Java is the contrast. Q&A format keeps the original doubts. Legend: ✅ covered · ⬜ todo (added as we go).*

**Through-line:** Java binds structure & dispatch at compile time (fixed, fast, rigid). Python resolves everything at runtime via dict lookups (flexible, dynamic, dispatch-by-search). Every OOP difference below flows from this.

---

# PART 1 — OOP CONCEPTS (Python primary · Java contrast)

## 1. Object model & attribute access ✅

**Q: What happens on `self.name = "Rex"`?**
It's `self.__dict__['name'] = "Rex"`. An instance is essentially a `dict`; attributes are entries → addable/removable at runtime. *Java: fixed compile-time fields, no runtime addition.*

**Q: Attribute lookup order for `obj.x`?**
data descriptors (class) → instance `__dict__` → class `__dict__` + parents (MRO) → non-data descriptors / class attrs → `__getattr__` / `AttributeError`. This single order explains descriptors, shadowing, and properties.

**Q: Class vs instance attribute — the `a.count += 1` trap?**
Reads the class attribute but **writes an instance attribute** that shadows it → `a` changes, `b`/class don't. Mutable class attrs (shared list) mutated via an instance affect all instances. *Java: `static` makes the distinction explicit.*

**Q: Why do SQLAlchemy columns use no decorators?**
`Mapped[str] = mapped_column(...)` is **annotation + assignment**, backed by descriptors. Decorators transform *functions*; columns declare *typed data attributes*. (Routes use `@app.get` because they wrap functions.)

## 2. Classes, `type` & inheritance mechanics ✅

**Q: Explain `type`.**
Two jobs: `type(x)` = an object's class; and `type` is the **metaclass** — the class that builds classes (`type(Dog)` is `type`). `class` is sugar for `type(name, bases, namespace)`. *Java: compile-time classes, reflection can inspect but not create; no metaclass.*

**Q: Fixed MRO order or do I look it up?**
Deterministic — **C3 linearization** (child before parents; parents in listed order; shared ancestor delayed until after its children; each once). Guarantees a unique order or refuses the class. Confirm via `Class.__mro__`. *Java: single inheritance, no C3 needed.*

**Q: What does `super()` mean?**
"Next class in the MRO", not "my parent" — with multiple inheritance it can call a sibling (cooperative dispatch). *Java: `super` = the one parent.*

## 3. Descriptors & the `@property` family ✅

**Q: What are data descriptors?**
Objects defining `__get__`/`__set__`/`__delete__`, stored as class attributes, intercepting access. **Data** = defines `__set__` (wins over instance `__dict__`). **Non-data** = only `__get__` (loses to instance `__dict__`). Per-instance state lives in `obj.__dict__` (descriptor is shared).

**Q: Descriptors vs Java?**
No equivalent. Java getters/setters weld logic to one field; a descriptor is a *reusable object* attachable to any attribute — only possible via Python's runtime dict-lookup model. `@property`, methods, `classmethod`/`staticmethod`, ORM columns are all descriptors.

**Q: `@property` + `@x.setter` — why write the setter if it "already has" set?**
Getter-only `@property` is **read-only** (assigning raises). `@x.setter` fills the setter slot; a property holds getter/setter/deleter slots, you fill only what you want. Storage uses `_x` to avoid setter recursion. *Java: replaces getter/setter ceremony with attribute syntax.*

**Q: Is a getter-only property data or non-data?**
**Data** — `property` *always defines* `__set__`; with no setter it just raises `AttributeError`. Category = "is `__set__` defined?", not "is it useful?". That's why read-only properties can't be silently shadowed (the `AttributeError` on assignment *is* `__set__` running).

**Q: `@classmethod` vs `@staticmethod`?**
`classmethod` gets `cls` → alternative constructors (`cls(...)` is subclass-correct). `staticmethod` gets nothing → grouped helper needing no state. Normal method gets `self`. *Java: static factory / static method — but Java statics can't be subclass-aware.*

## 4. Encapsulation & access specifiers ✅

**Q: Explain access specifiers, Python vs Java.**
Core difference: **Java enforces access; Python signals it by convention.**

Java — four compiler-enforced levels: `private` (class only), *package-private* (default, same package), `protected` (package + subclasses), `public` (everywhere). `private` is a hard guarantee — outside code *cannot* compile against it. This is why Java uses private fields + public getters/setters.

Python — **no keywords; everything is technically public.** Three convention levels:
- `name` — public, use freely.
- `_name` — "internal, please don't touch" (single underscore). A signal, not a barrier — `obj._x` still works. ≈ Java protected/private *intent*.
- `__name` — **name mangling**: renamed to `_ClassName__name`. Blocks casual access (`obj.__x` → `AttributeError`) but reachable via `obj._ClassName__x`. Its real purpose is avoiding subclass name clashes, not true privacy — a speed bump, not a lock.

Philosophy: Java "the compiler enforces boundaries"; Python "we're all consenting adults here" — it trusts developers and values flexibility (debugging, testing, monkey-patching) over enforced walls. Same *intent* (encapsulation), opposite *means*: Java compiles a wall, Python writes a note.

Mapping: `public`→`name`, `protected`→`_name`, `private`→`__name` (bypassable). In your project, `self._balance` behind a `@property` is exactly the Pythonic "private field + public getter" — signal the raw field `_x`, expose a property.

## 5. Data classes ✅

**Q: What is a `@dataclass`?**
A decorator that **auto-generates** `__init__`, `__repr__`, `__eq__` from annotated fields — kills boilerplate for data-holding classes.
```python
@dataclass
class Point:
    x: int
    y: int
# gives __init__, __repr__ (Point(x=1, y=2)), __eq__ (value equality) for free
```
Options: `frozen=True` (immutable + hashable), `order=True` (adds `__lt__` etc. → sortable). Mutable defaults must use `field(default_factory=list)` (never `tags: list = []` — the shared-mutable trap).

**Q: Dataclass vs Java?**
≈ **Java `record`** (Java 16+) or **Lombok `@Data`**. Java `record` is always immutable → `@dataclass(frozen=True)` ≈ `record`; plain `@dataclass` ≈ mutable data class. Java `record` is a language construct; `@dataclass` is a decorator reading `__annotations__`.

**Q: Dataclass vs Pydantic?**
`@dataclass` = boilerplate reduction, **no runtime type enforcement**. Pydantic `BaseModel` = boilerplate **plus runtime validation** (bad data → error). Rule: `@dataclass` for internal data you control; **Pydantic for external data you must validate** (API bodies, config). Use case in project: a `ClickEvent` dataclass for internal event objects; Pydantic for request/response schemas.

## 6. Abstraction & interfaces ⬜ (todo)
- ABCs — `abc.ABC` + `@abstractmethod`, enforced at instantiation (runtime) · *Java: `abstract class`, compile-time*
- Protocols — `typing.Protocol` (structural typing) vs `ABC` (nominal) · *Java: `interface` (nominal)*
- Duck typing philosophy · *Java: explicit `implements` required*

## 7. Inheritance & polymorphism ⬜ (todo)
- single / multiple / **mixins** · *Java: single class + multiple interfaces + default methods*
- method **overriding** (both) vs **overloading** — Python has *no* true overloading (use default args / `@singledispatch`) · *Java: true signature-based overloading*
- polymorphism: duck-typed vs subtype/interface
- composition vs inheritance (design principle)

## 8. Enums ⬜ (todo)
- `enum` module — `Enum`, `IntEnum`, `StrEnum`, `auto()` · *Java: `enum` is richer — fields, constructors, methods*

## 9. Object contracts & value semantics ⬜ (todo)
- `__eq__` / `__hash__` consistency contract · *Java: `equals()` / `hashCode()` contract*
- immutability — `frozen` dataclass, `tuple`, `namedtuple` · *Java: `final`, `record`*
- copying — `copy` / `deepcopy` · *Java: `clone()` / `Cloneable`*
- `__repr__` vs `__str__` (dev vs user string) 🔸

## 10. Iteration & resource management ⬜ (todo)
- iterators — `__iter__` / `__next__`, `StopIteration` · *Java: `Iterable` / `Iterator`*
- context managers — `__enter__` / `__exit__`, `contextlib` · *Java: try-with-resources / `AutoCloseable`*

## 11. Advanced OOP ⬜ (todo, lower priority)
- generics — `typing.Generic`, `TypeVar` · *Java: generics + type erasure*
- nested / inner classes · *Java: inner, static nested, anonymous*
- metaclasses (deep) — customizing class creation · *Java: none*
- class decorators & `__init_subclass__`
- sealed hierarchies — *Java: `sealed` (17+)*; Python: no direct equivalent
- exceptions as OOP — custom exception classes/hierarchy · *Java: checked vs unchecked (Python has no checked exceptions)*

---

# PART 2 — RUNTIME INTERNALS

## Memory model ✅
No primitives — **everything is a heap object**. Variables are **names bound to objects** (references) → `a = b` aliases, not copies. Freed by **reference counting + cyclic GC** (for reference cycles). Small ints (−5..256) and some strings **interned**. `is` = identity, `==` = value (`__eq__`). *Java: primitives vs objects, stack vs heap, tracing GC, no refcounting.*

## `__slots__` ✅
Removes the per-instance `__dict__`, storing attrs in fixed slots (like Java fields at fixed offsets) → big memory savings + faster access. Only worth it for *many* instances of a simple class. Trade-off: no dynamic attributes.

## GIL & concurrency ✅
**GIL** = only one thread runs Python bytecode at a time per process → threads give concurrency (I/O), not multi-core CPU parallelism within one process. **Concurrency** = one worker switching between tasks (event loop). **Parallelism** = multiple workers on different cores simultaneously (needs multiple processes). *Java: real parallel threads + JMM.*

## Event loop / uvloop ✅
Single thread starts many I/O ops and parks whichever is *waiting* (`await`), resuming when ready — non-blocking I/O. **uvloop** = Cython/libuv event loop (the engine Node uses) → ~2–4× faster than default asyncio. Camps: explicit event loop (Python-async, Node) vs runtime-hidden lightweight threads (Go goroutines, Java virtual threads — true multi-core built in).

## Bytecode / `__pycache__` ✅
Imported modules compile to **bytecode** (`.pyc`) cached here, so imports skip recompiling. Version-tagged (`cpython-312`). Generated artifact → gitignore, never edit, safe to delete. ≈ Java `.class` files.

## Language features ⬜ (todo)
- decorators under the hood (closures, functions returning functions, `functools.wraps`)
- closures & scoping (LEGB, `nonlocal`/`global`)
- generators & `yield` (lazy evaluation)
- comprehensions (list/dict/set)
- `*args` / `**kwargs`, keyword-only args, unpacking
- exception model — EAFP vs LBYL, `try/except/else/finally` · *Java: checked exceptions*
- type hints / `typing` — `Optional`, `|`, `Literal`, `Generic`, `Protocol`, `Annotated`
- `functools` (`lru_cache`, `partial`, `wraps`, `singledispatch`), `itertools`
- collections — `Counter`, `defaultdict`, `deque`, `namedtuple`, `heapq`, `bisect` (≈ Java Collections)
- modules, packages, imports (`__init__.py`, absolute vs relative)
- concurrency hands-on — `threading`, `multiprocessing`, `asyncio`, `concurrent.futures`

---

# TOOLING ✅

**Q: uv vs pip; Python version?**
pip installs packages; **uv** (Rust) also manages venvs, Python versions, lockfiles — 10–100× faster (`uv add`, `uv run`). Use **Python 3.12** (widest lib compatibility); 3.14 too new (library lag). *Java prod pins LTS (21); Python has ~5-yr support windows, no formal LTS.*

**Q: `__pycache__`?** — see Bytecode above.

---

*Documentation principle: every concept ends as "Python: <impl> · Java: <contrast>". This file is the growing OOP-in-both-languages reference; ⬜ items get filled as we cover them.*