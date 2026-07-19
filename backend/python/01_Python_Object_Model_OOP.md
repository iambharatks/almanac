# 01 · Python Object Model & OOP

> ⭐ **The one idea:** Java binds structure & dispatch at **compile time** (fixed, fast, rigid). Python resolves everything at **runtime via dict lookups** (flexible, dynamic, dispatch-by-search). Every difference below follows from this.

---

## 1. Objects are dicts

`self.name = "Rex"` literally does `self.__dict__['name'] = "Rex"`.
An instance is essentially a dictionary; attributes are entries → you can add/remove them at runtime.

*Java: fixed compile-time fields; assigning an undeclared field is a compile error.*

## 2. Attribute lookup order ← memorise this

Accessing `obj.x` searches:

1. **Data descriptors** on the class (define `__set__`/`__delete__`)
2. **Instance `__dict__`**
3. **Class `__dict__`**, then parents along the **MRO**
4. **Non-data descriptors** / plain class attributes (only `__get__`)
5. `__getattr__` if defined, else `AttributeError`

This single order explains descriptors, shadowing, and properties. *Java: vtable + fixed offsets, resolved ahead of time.*

## 3. Class vs instance attributes (the shadowing trap)

```python
class Counter:
    total = 0
a, b = Counter(), Counter()
a.total += 1        # READS class 0, WRITES to a's own __dict__
# a.total=1 · b.total=0 · Counter.total=0
```

⚠️ A **mutable** class attribute (a shared list) *mutated* through an instance affects **all** instances.
*Java: `static` makes this explicit and unambiguous.*

## 4. Classes are objects; `type` is the metaclass

- `type` has **two jobs**: `type(x)` returns x's class; and `type` is the class-of-classes that **builds** classes (`type(Dog)` is `type`).
- `class Foo: ...` is sugar for `type("Foo", bases, namespace)`.
- A **metaclass** = "the class of a class"; `type` is the default. Custom ones are rare (ABCs, Django).

*Java: classes are compile-time; reflection can inspect but not create/alter them; no metaclass concept.*

## 5. MRO & C3 · `super()`

**C3 linearization** gives a deterministic order: child before parents · parents in listed order · each class once · a shared ancestor delayed until after all its children. Inconsistent hierarchies → `TypeError` at class creation. Check with `Class.__mro__`.

```python
class D(B, C): ...        # B, C both inherit A
# MRO: D, B, C, A, object   (A last — shared parent, delayed)
```

**`super()` = "the next class in the MRO"**, *not* "my parent" — with multiple inheritance it can call a **sibling** (cooperative dispatch).

*Java: single class inheritance (+ interfaces) → no diamond, no C3; `super` is unambiguous.*

## 6. Dunder methods

Operators are sugar over dunders: `a+b`→`__add__`, `a==b`→`__eq__`, `len(a)`→`__len__`, `a[i]`→`__getitem__`, `print(a)`→`__str__`/`__repr__`.

| | Identity | Value |
|---|---|---|
| Python | `is` | `==` (calls `__eq__`) |
| Java | `==` | `.equals()` |

⚠️ Java-`==` ≈ Python-`is`. Java-`equals` ≈ Python-`==`.

## 7. Descriptors

An object defining `__get__` / `__set__` / `__delete__`, stored as a **class attribute** → routes attribute access through its methods.

| | Defines | Lookup priority |
|---|---|---|
| **Data descriptor** | `__set__`/`__delete__` | **wins over** instance `__dict__` |
| **Non-data descriptor** | only `__get__` | **loses to** instance `__dict__` (shadowable) |

Store per-instance state in `obj.__dict__` — the descriptor object is **shared** across instances.

**Secretly descriptors:** `@property`, **methods** (their `__get__` binds `self`), `classmethod`, `staticmethod`, **ORM columns** (SQLAlchemy `mapped_column`, Pydantic fields).

*Java: no equivalent. Getters/setters weld logic to one field; a descriptor is a **reusable object** attachable to any attribute — only possible because of runtime dict lookup.*

## 8. `@property` (+ setter) — and the classic trap

- `@property` = method accessed like an attribute; defines the **getter**.
- A getter-only property is **READ-ONLY** — assigning raises `AttributeError`.
- `@x.setter` fills the setter slot; `@x.deleter` the deleter. A `property` holds **3 slots**; you fill only what you want.
- Store the raw value under a different name (`self._x`) to avoid infinite recursion.

> ⚠️ **The trap: even a getter-only property is a DATA descriptor.**
> `property` **always defines** `__set__`; with no setter that `__set__` just **raises**. The category is decided by *"is `__set__` defined?"*, not *"is it useful?"*
> → That `AttributeError` **is `__set__` executing and refusing**, and it's *why* a read-only property can't be silently shadowed via `obj.__dict__`.
> A plain **method** has no `__set__` → truly non-data → *can* be shadowed with no error.

*Java: replaces getter/setter ceremony — callers use `a.balance` instead of `getBalance()`. Writing only a getter in Java = a property with no setter in Python: the same deliberate read-only choice.*

## 9. `classmethod` vs `staticmethod`

| | Receives | Called on | For |
|---|---|---|---|
| instance method | `self` | instance | behaviour on object data |
| `@classmethod` | `cls` | class | **alternative constructors**, class state |
| `@staticmethod` | nothing | class | related helper needing no state |

```python
@classmethod
def from_string(cls, s):
    return cls(...)      # cls → subclass-correct construction
```

*Java: `classmethod` ≈ static factory **but inheritance-aware** (Java statics hardcode the type); `staticmethod` ≈ plain `static` method.*

## 10. Encapsulation & access specifiers

**Java enforces access; Python signals it by convention.**

| Intent | Java (compiler-enforced) | Python (convention) |
|---|---|---|
| Public | `public` | `name` |
| Internal | `protected` / package-private | `_name` — "don't touch"; **still accessible** |
| Private | `private` | `__name` — **name-mangled** to `_Class__name` |

- `__name` is a **speed bump, not a lock**: `obj.__x` → `AttributeError`, but `obj._Class__x` works. Its real purpose is **avoiding subclass name clashes**, not privacy.
- Philosophy: Java = "the compiler enforces boundaries"; Python = *"we're all consenting adults here"* — trusts developers, values flexibility (debugging, testing, monkey-patching) over walls.
- In practice: `self._balance` behind a `@property` **is** the Pythonic "private field + public getter".

## 11. `@dataclass`

Auto-generates `__init__`, `__repr__`, `__eq__` from annotated fields.

```python
@dataclass
class Point:
    x: int
    y: int
# → Point(1,2) works · print → Point(x=1, y=2) · == is value equality
```

- `frozen=True` → **immutable** + hashable · `order=True` → adds `__lt__` etc. (sortable)
- ⚠️ Mutable defaults **must** use `field(default_factory=list)` — never `tags: list = []` (shared across instances)

| | `@dataclass` | Pydantic `BaseModel` |
|---|---|---|
| Boilerplate | ✅ | ✅ |
| **Runtime validation** | ❌ (hints not enforced) | ✅ |
| Use for | internal data you control | **external data you must validate** (API bodies, config) |

*Java: `@dataclass(frozen=True)` ≈ **`record`** (Java 16+, always immutable); plain `@dataclass` ≈ Lombok `@Data`. Java `record` is a language construct; `@dataclass` is a decorator reading `__annotations__`.*

---

## 🎤 Interview answers

**"How does `@property` work under the hood?"**
> It's a data descriptor stored as a class attribute. Because data descriptors beat the instance `__dict__` in the lookup order, accessing the attribute always routes through the property's `__get__`. It holds three slots — getter, setter, deleter — and a property *always* defines `__set__`, which just raises if you didn't supply a setter. That's why read-only properties can't be silently shadowed by an instance attribute.

**"Python vs Java OOP — the core difference?"**
> Java fixes an object's structure and method dispatch at compile time; Python resolves attributes at runtime by searching dictionaries along the MRO. That's why Python gets multiple inheritance with C3, runtime-modifiable classes, and descriptors — and why Java gets speed and compile-time guarantees like enforced `private`.

**"Multiple inheritance — how does Python avoid the diamond problem?"**
> C3 linearization computes a deterministic MRO: child before parents, declared order preserved, each class once, shared ancestors delayed until after their children. If no consistent order exists, the class fails to be created. `super()` follows that MRO rather than jumping to a parent, which makes cooperative multiple inheritance work.

---

## ✅ Gotcha checklist

- [ ] `a.count += 1` on a class attribute → creates an instance attribute that shadows it
- [ ] Mutable class attribute is shared across all instances
- [ ] `super()` = next in MRO, not parent
- [ ] Getter-only `@property` is still a **data** descriptor (always has `__set__`)
- [ ] Methods are non-data → shadowable; properties are data → not
- [ ] Use `cls(...)` in classmethods so subclasses construct correctly
- [ ] `__name` mangling ≠ privacy
- [ ] `field(default_factory=...)` for mutable dataclass defaults
- [ ] Java `==` ≈ Python `is`
