# 02 · Python Memory Model & Internals

> ⭐ **The one idea:** Python has **no primitives** — everything is a heap object, and variables are **names bound to objects** (references). Memory is freed by **reference counting** (immediate, at zero) plus a **cyclic GC** (for reference cycles). The **GIL** is effectively Python's concurrency memory model.

---

## 1. Everything is a heap object

There are no primitives. The integer `5`, a string, a function, a class — all heap objects.
`x = 5` does **not** put `5` in a box called `x`; it creates/reuses an int object and **binds the name** `x` to it.

*Java: `int`/`double` are primitives (stack or inline); only objects are on the heap.*

## 2. Names are references → aliasing

```python
a = [1, 2, 3]
b = a           # same object, NOT a copy
b.append(4)
print(a)        # [1, 2, 3, 4]
```

Assignment **binds a name**; it never copies. Since there are no primitives, *everything* is reference semantics — which is why aliasing surprises are common.

*Java: same for objects, but primitives are true value-copies.*

## 3. Reference counting (primary)

Every object tracks how many references point to it. Bind a name → +1. Rebind/`del`/scope exit → −1. **At zero, freed immediately.**

```python
import sys
x = []
y = x            # count +1
del y            # count -1
```

*Java: **no** refcounting — a tracing generational GC finds unreachable objects periodically.*
**Trade-off:** Python frees eagerly and predictably but pays per-operation overhead; Java frees in GC cycles with no per-op cost.

## 4. Cyclic GC (backup)

Refcounting **cannot** free reference cycles: A→B and B→A with nothing else pointing at them — counts never hit zero though both are unreachable. A separate **cyclic collector** detects and reclaims these.

```python
a, b = {}, {}
a['b'] = b; b['a'] = a      # cycle
del a, b                    # only the cyclic GC can reclaim
```

**Model:** refcounting *primary* + cyclic GC *backup*.

## 5. Interning (and `is` vs `==`)

Python pre-creates/caches some immutables: **small ints −5..256** and some short strings.

```python
a = 256; b = 256; a is b     # True  — same cached object
a = 257; b = 257; a is b     # often False
```

- `is` → **identity** (same object) · `==` → **value** (`__eq__`)
- ⚠️ Interning makes `is` *accidentally* work for small ints, misleading people into using it for equality. **Use `==` for value.**

## 6. Memory pools (the allocator)

CPython doesn't `malloc` per object. **pymalloc** takes big blocks (**arenas**) from the OS, splits them into **pools** and **blocks** sized for small objects. This is why constant creation/destruction of small objects is fast.

## 7. `__slots__`

Normal instances carry a per-instance `__dict__` (a hash table: over-allocated buckets, keys, hashes) — expensive when you have **millions** of instances.

```python
class PointSlots:
    __slots__ = ('x', 'y')     # no __dict__; fixed slots
```

| | Normal instance | `__slots__` |
|---|---|---|
| Storage | per-instance `dict` | **fixed slots** (offsets) |
| Memory | heavy | **~half or less** |
| Attribute access | hash lookup | **fixed offset** (faster) |
| Dynamic attributes | ✅ | ❌ `AttributeError` |

**Model:** normal instance = a **dict** (flexible, heavy); slotted = a **struct** (fixed, light).
**Use when:** many instances of a simple fixed-shape class (nodes, points, records). Otherwise skip — flexibility is worth more.
⚠️ Subclasses need their own `__slots__`; if any class in the chain lacks it, `__dict__` returns.

*Java: objects **always** have fixed fields at fixed offsets — `__slots__` is Python opting into the Java object model.*

## 8. Bytecode & `__pycache__`

Python isn't purely line-by-line interpreted: source is **compiled to bytecode** first, then executed by the VM.

- Imported modules are cached as `.pyc` in `__pycache__` so imports skip recompiling.
- Version-tagged (`main.cpython-312.pyc`) — bytecode isn't cross-version compatible.
- Python checks if the source changed; if not, loads the cache.
- **Generated artifact:** gitignore it, never edit it, safe to delete (regenerates).

*Java: ≈ `.class` files — but Java makes compilation an **explicit** step (`javac`) while Python compiles on the fly and caches transparently.*

## 9. Memory model vs Java (summary)

| | Java | Python |
|---|---|---|
| Primitives | ✅ (stack/inline) | ❌ — everything heap |
| Variables | typed boxes | **names → object refs** |
| Reclamation | tracing generational GC | **refcount + cyclic GC** |
| Freed when | GC cycle decides | **immediately at zero refs** |
| Interning | — | small ints (−5..256), some strings |
| Concurrency model | formal **JMM** (`volatile`, `synchronized`, happens-before) | **GIL serializes bytecode** → no JMM equivalent needed |

## 10. Tooling

**uv vs pip**

| Task | pip (old) | uv |
|---|---|---|
| Env | `python -m venv` + activate | automatic |
| Install | `pip install X` | `uv add X` |
| Dev-only dep | (no separation) | `uv add --dev X` |
| Record deps | `pip freeze > requirements.txt` | automatic (`pyproject.toml` + `uv.lock`) |
| Run | activate, then cmd | `uv run cmd` |
| Python version | separate `pyenv` | built-in (`uv init --python 3.12`) |

**uv** (Rust) = installer **+** venv manager **+** Python-version manager **+** lockfiles, **10–100× faster**. pip only installs.

**Versions:** use **Python 3.12** (widest library compatibility). 3.14 is too new — libraries lag, causing install errors unrelated to your code. *Java pins **LTS** (21); Python has ~5-year support windows, no formal LTS.*

**Import paths:** `sys.path` is populated by **how you launched**, which is why `src` imports work under `uvicorn src.main:app` from the root but fail under pytest until you set `pythonpath = ["."]`. *Java: the build tool manages the classpath — this class of problem barely exists.*

---

## 🎤 Interview answers

**"How does Python manage memory?"**
> Everything is a heap object and variables are references to them. The primary mechanism is reference counting — when an object's count hits zero it's freed immediately. Because refcounting can't reclaim reference cycles, there's a secondary cyclic garbage collector. Small integers and some strings are interned and shared, and CPython uses a pooled allocator so churn of small objects is cheap.

**"Python GC vs Java GC?"**
> Java uses a tracing generational collector — it periodically finds unreachable objects, so freeing is deferred but there's no per-operation cost. Python refcounts, so cleanup is immediate and deterministic, at the cost of overhead on every reference change, plus a cycle collector for what refcounting misses.

**"When would you use `__slots__`?"**
> When I'm creating a very large number of instances of a simple, fixed-shape class. It removes the per-instance `__dict__`, storing attributes at fixed offsets — roughly halving memory and speeding attribute access. The trade-off is losing dynamic attributes, and it complicates inheritance, so it's a targeted optimisation rather than a default.

---

## ✅ Gotcha checklist

- [ ] `a = b` aliases; it never copies
- [ ] Mutable default arguments (`def f(x=[])`) are shared across calls
- [ ] `is` vs `==` — interning makes `is` deceptively "work" for small ints
- [ ] Refcounting alone can't free cycles
- [ ] `__slots__` only pays off at high instance counts
- [ ] `__pycache__` is generated — gitignore, never commit
- [ ] Import success depends on how you launched (`sys.path`)
