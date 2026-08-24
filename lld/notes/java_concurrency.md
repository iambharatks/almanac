# LLD, Java Internals & Concurrency — Revision Notes

> Personal study notes. Written for re-reading before an interview, not for
> first-time learning. Every section ends with the one-line answer you'd
> actually give out loud.

---

## Table of Contents

1. [Object Modelling: The Core Distinction](#1-object-modelling-the-core-distinction)
2. [SOLID — With Real Violations](#2-solid--with-real-violations)
3. [Creational Patterns](#3-creational-patterns)
4. [Java Type System for Design](#4-java-type-system-for-design)
5. [How a Java Program Actually Runs](#5-how-a-java-program-actually-runs)
6. [Memory Management](#6-memory-management)
7. [The Java Memory Model](#7-the-java-memory-model)
8. [Synchronization Primitives](#8-synchronization-primitives)
9. [The Universal Concurrency Bug: Check-Then-Act](#9-the-universal-concurrency-bug-check-then-act)
10. [Testing Concurrent Code](#10-testing-concurrent-code)
11. [Case Study: Parking Lot](#11-case-study-parking-lot)
12. [Cross-Language Transfer](#12-cross-language-transfer)
13. [Interview Quick Reference](#13-interview-quick-reference)

---

## 1. Object Modelling: The Core Distinction

Almost every LLD decision traces back to one question:

> **If two instances have identical field values, are they the same thing?**

### Value Objects — "yes, same thing"

`Money(100, "INR")` **is** the same hundred rupees as any other `Money(100, "INR")`.
There is no "which one" — they are interchangeable.

- Equality is **structural** (all fields)
- Freely created; `new` costs nothing conceptually
- Should be **immutable**
- In Java: use a `record`

### Entities — "no, different things"

Truck #47 is truck #47 regardless of where it currently is. Two trucks parked at
the same depot with the same fuel level are still two different trucks.

- Equality is **by identity** (usually just the ID)
- Finite in number; you *acquire* them, you don't conjure them
- Usually **mutable** (state changes over their lifetime)
- In Java: use a `final class` with `equals` on the ID

### Why this matters beyond naming

The distinction drives three separate downstream decisions:

| Decision | Value object | Entity |
|---|---|---|
| Create freely, or acquire from a pool? | Create freely | Acquire (finite) |
| Needs synchronization? | No (immutable) | Yes (mutable shared state) |
| Can you lock on it? | Meaningless (many copies) | Yes, if canonical |

**The chain that follows from it:**

```
Is it an entity?
  → it's finite and stateful
    → you acquire it rather than create it
      → the acquisition is a shared-resource claim
        → that claim is where the race condition lives
          → and you can only lock on it if there's ONE canonical instance
```

That last link is the non-obvious one: **object identity constrains your locking
options.** If `Seat` objects are created freely per request, `synchronized(seat)`
does nothing — different instances have different monitors, so two threads both
enter their block and neither blocks.

### Where the invariant lives

> **Model something as a single shared instance when mutable state must stay
> consistent AND the heap is the authority for it. Otherwise create freely and
> enforce scarcity through explicit state.**

Four cases where "one object per real thing" does *not* apply:

1. **The truth lives in a database.** A reporting service builds a fresh `Truck`
   object per request from DB rows. Two concurrent requests produce two objects for
   the same physical truck — fine, because neither mutates. Identity is the primary
   key, not the reference. *(Introduce writes and this collapses — which is exactly
   why ORMs have identity maps.)*

2. **Scarcity is enforced by state, not object count.** A cinema has 200 seats.
   You do **not** pool 200 `Seat` objects — you create `Seat` freely and enforce
   scarcity via a *booking registry* (`Map<SeatId, UserId>`). Scarcity is a fact
   about the registry, not about how many `Seat` instances exist in memory.

3. **Identity is irrelevant to behaviour.** A chess set has two white bishops, but
   promote pawns and you can have nine queens. *Which* queen sits on d8 doesn't
   matter — only type and position. Model `Piece` as an immutable value; the board
   holds the position state.

4. **Deliberately many instances to avoid contention.** `SimpleDateFormat` isn't
   thread-safe. Rather than pool one behind a lock, give each thread its own.
   Cheap allocation beats lock contention.

**Interview line:**
> "A truck is an entity — finite, stateful, identity matters — so I acquire it
> from a pool rather than constructing one. A seat is different: I create `Seat`
> objects freely and enforce scarcity in a booking registry, because that's where
> the invariant actually lives."

---

## 2. SOLID — With Real Violations

Read the violation, spot the smell before reading the fix.

### SRP — One reason to change

```java
// VIOLATION: three reasons to change
class Order {
    List<Item> items;
    double calculateTotal()      { /* pricing rules */ }
    void saveToDb()              { /* persistence */ }
    void sendConfirmationEmail() { /* notification */ }
}
```

`Order` changes when pricing changes, when the schema changes, **and** when the
email template changes. Three teams, one class.

```java
// FIX
class Order { List<Item> items; }
class OrderPricer         { double calculateTotal(Order o) { ... } }
class OrderRepository     { void save(Order o) { ... } }
class NotificationService { void sendConfirmation(Order o) { ... } }
```

**Tell:** the class name contains "and", or a method touches an unrelated concern.

### OCP — Open for extension, closed for modification

```java
// VIOLATION: every new vehicle type edits this method
double fee(Vehicle v) {
    if (v.type == CAR)   return 20;
    if (v.type == BIKE)  return 10;
    if (v.type == TRUCK) return 40;   // ...and again, forever
    return 0;
}
```

```java
// FIX: Strategy
interface FeeStrategy { double fee(Duration d); }
class CarFee  implements FeeStrategy { public double fee(Duration d) { return 20 * d.toHours(); } }
class BikeFee implements FeeStrategy { public double fee(Duration d) { return 10 * d.toHours(); } }

class FeeCalculator {
    private final Map<VehicleType, FeeStrategy> strategies;
    double fee(VehicleType t, Duration d) { return strategies.get(t).fee(d); }
}
```

**This is the highest-value snippet on the page.** A growing `if/else` on a type
field is the single most common LLD smell, and Strategy is the fix in Parking Lot,
Cab Booking, Splitwise, and Rate Limiter.

### LSP — Subtypes must be substitutable

```java
// VIOLATION: the classic
class Rectangle {
    protected int w, h;
    void setWidth(int w)  { this.w = w; }
    void setHeight(int h) { this.h = h; }
    int area() { return w * h; }
}
class Square extends Rectangle {
    void setWidth(int w)  { this.w = w; this.h = w; }   // surprises the caller
    void setHeight(int h) { this.h = h; this.w = h; }
}
// r.setWidth(5); r.setHeight(4); assert r.area() == 20;   // fails for Square
```

```java
// FIX: they aren't behaviourally substitutable, so don't inherit
interface Shape { int area(); }
class Rectangle implements Shape { private final int w, h; public int area() { return w * h; } }
class Square    implements Shape { private final int side; public int area() { return side * side; } }
```

**Tell:** an override throws `UnsupportedOperationException`, or narrows what the
parent promised. Inheritance was wrong — reach for composition.

### ISP — No client forced to depend on methods it doesn't use

```java
// VIOLATION
interface Worker { void work(); void eat(); void attendMeeting(); }
class RobotWorker implements Worker {
    public void work() { ... }
    public void eat() { throw new UnsupportedOperationException(); }   // smell
}
```

```java
// FIX
interface Workable { void work(); }
interface Feedable { void eat(); }
class HumanWorker implements Workable, Feedable { ... }
class RobotWorker implements Workable { ... }
```

**Tell:** empty method bodies or `UnsupportedOperationException` in an implementation.

**Real use in Parking Lot** — separating configuration from operation:

```java
interface ParkingOperations {      // what the API layer sees
    Ticket park(Vehicle v);
    Money  unpark(Ticket t);
}
interface ParkingAdministration {  // what setup sees
    void addFloor(Floor f);
    void closeFloor(int floorId);
}
final class ParkingLot implements ParkingOperations, ParkingAdministration { ... }
```

One object, two views. A caller holding `ParkingOperations` **cannot** restructure
the lot — the method isn't on the type. This beats an `Admin` class, which would
model your org chart instead of your domain.

### DIP — Depend on abstractions

```java
// VIOLATION: high-level policy welded to a low-level detail
class NotificationService {
    private final EmailSender sender = new EmailSender();   // hard-wired
    void notify(String msg) { sender.sendEmail(msg); }
}
```

```java
// FIX: constructor injection, no framework needed
interface MessageSender { void send(String msg); }
class NotificationService {
    private final MessageSender sender;
    NotificationService(MessageSender sender) { this.sender = sender; }
    void notify(String msg) { sender.send(msg); }
}
```

**Tell:** `new` on a concrete dependency inside a class that shouldn't own its
lifecycle.

**The generalised rule this produces:**

> **Configuration flows down from the top. Nothing constructs its own dependencies.**
> `main()` builds the rate table → the rates go into the strategy → the strategy
> goes into the lot.

This applies recursively. Hardcoding rates inside `ParkingLot` is wrong; moving
them inside `SizeBasedPricing` as a `static final` is *also* wrong — it just
relocates the coupling. The class owns the **algorithm**; the caller owns the
**values**.

---

## 3. Creational Patterns

### Factory Method

**One method that makes one thing**, chosen by a subclass.

```java
abstract class ExportJob {
    protected abstract Writer createWriter();     // THE factory method

    public final void export(List<Record> rows) { // the invariant algorithm
        Writer w = createWriter();                // the only variable step
        System.out.println(w.header());
        for (Record r : rows) System.out.println(w.row(r));
    }
}
class CsvExportJob  extends ExportJob { protected Writer createWriter() { return new CsvWriter(); } }
class JsonExportJob extends ExportJob { protected Writer createWriter() { return new JsonWriter(); } }
```

**The shape:** a base class with a real algorithm that has exactly one hole in it.
The subclass overrides only the *creation step*, never the behaviour. That
inversion of control is the pattern.

**Two non-obvious properties:**

1. **The return type must be the interface.** Java permits covariant returns
   (`Truck createTransport()`), but the moment a caller sees the concrete type you've
   leaked what you were buying.

2. **It needn't create anything new.** The method is named *create* but the contract
   is *supply*. This is legal and the caller can't tell:

```java
protected Transport createTransport() {
    Transport t = pool.poll();
    return (t != null) ? t : new Truck();      // reuse or allocate
}
```

That freedom exists *because* the return type is the abstraction. `Integer.valueOf(5)`
does exactly this — cached for small values, allocated for large.

### Abstract Factory

**One object that makes a family of things that must match.**

```java
interface SqlDialect {
    Quoter    quoter();
    Paginator paginator();
    TypeMapper types();
}
class PostgresDialect implements SqlDialect { /* all Pg* */ }
class MySqlDialect    implements SqlDialect { /* all My* */ }

class Orm {
    private final SqlDialect d;                 // ONE dialect. Cannot mix.
    Orm(SqlDialect d) { this.d = d; }
}
```

### The actual difference

**Not the count of products. The consistency constraint.**

Factory Method *can* express three products via three abstract methods. What it
cannot do is **enforce** that they came from the same family:

```java
class SloppyPostgresOrm extends FmOrm {
    protected Quoter createQuoter()         { return new PgQuoter(); }   // Postgres
    protected TypeMapper createTypeMapper() { return new MyTypeMapper(); } // MySQL ← BUG
}
```

This **compiles and runs**. It emits `"active" = 1` — Postgres quoting with MySQL
booleans, a runtime type error far from its cause. `javac` has no opinion because
nothing ever expressed that `PgQuoter` and `MyTypeMapper` belong to different
families. Family membership existed only in the developer's head and the class name.

**But be honest about the guarantee.** Someone can still write a broken
`PostgresDialect` that returns a `MyTypeMapper`. Abstract Factory doesn't make the
mistake impossible — it **localises** it:

- Factory Method: the mistake is available in *every subclass*, forever
- Abstract Factory: the mistake is available in *one small, named, testable class*

And that one place is qualitatively easier to defend — a 6-line class whose entire
job is "return Postgres parts", where a wrong return type is glaring in a diff, and
where one unit test covers the family forever.

> **Patterns rarely make errors impossible. They concentrate where errors can
> occur, so that naming, tests, and review can reach them.**

**The general principle underneath:** encode invariants in **types**, not in
**conventions**. "All parts must be the same vendor" as a convention is a rule
humans must remember. As a structural fact, the compiler enforces it for free.

**They sit on different axes and compose:**

| Axis | Varies | Pattern | Mechanism |
|---|---|---|---|
| Which cloud | Compute + Storage + LoadBalancer, must match | Abstract Factory | Composition (injected) |
| Which rollout | Rollout, single product | Factory Method | Inheritance (override) |

2 clouds × 2 rollouts = 4 behaviours from 4 small classes, not 4 combination
classes. Adding Azure: one class, zero pipeline changes.

**Interview line:**
> "Factory Method creates one product and you vary it by subclassing. Abstract
> Factory creates a family of related products and you vary it by injecting a
> different factory. You reach for the second when mismatching the products would
> be a bug."

### Builder

```java
public class Pizza {
    private final String size;
    private final List<String> toppings;

    private Pizza(Builder b) {
        this.size = b.size;
        this.toppings = List.copyOf(b.toppings);   // MANDATORY defensive copy
    }

    public static class Builder {                  // STATIC — load-bearing
        private String size = "M";
        private List<String> toppings = new ArrayList<>();
        public Builder size(String s) { this.size = s; return this; }  // return this = chaining
        public Pizza build() { return new Pizza(this); }
    }
}
```

**Why `static` matters:** a non-static inner class holds an implicit `Outer.this`
reference — you'd need a `Pizza` before you could create its Builder. Chicken and
egg. `new Pizza.Builder()` only compiles because it's static.

**The JMM payoff.** All fields `final` + no `this` escape ⇒ **safe publication for
free**. Hand that `Pizza` to any number of threads with zero synchronization. See §7.

**The shape to remember:**
> mutable accumulation in one thread → immutable snapshot at `build()` → safe to
> share everywhere.

The Builder itself is *not* thread-safe and shouldn't be — it's short-lived and
thread-confined by construction.

**Modern shortcut:** for 3–4 fields, a `record` with a compact constructor does the
same job in one line. Use a Builder only when you have many optional parameters.

### Singleton

```java
public final class ParkingLot {
    private ParkingLot() { ... }

    private static class Holder {                       // "Bill Pugh" / holder idiom
        static final ParkingLot INSTANCE = new ParkingLot();
    }
    public static ParkingLot getInstance() { return Holder.INSTANCE; }
}
```

**Why it's lazy:** the JVM defers **class initialization** until first *active use*.
Loading `ParkingLot` does not initialize `Holder`; reading `Holder.INSTANCE` does.

**Why it's thread-safe:** the JVM guarantees `<clinit>` runs exactly once, under its
own internal init lock. Other threads block until initialization completes. You are
borrowing the classloader's lock instead of writing one — which is why there's no
`volatile`, no `synchronized`, no double-check.

**Versus double-checked locking:** DCL is correct *if* the field is `volatile`. Drop
the `volatile` and another thread can see a non-null reference to a half-constructed
object, because the reference publication can be reordered ahead of the constructor's
writes. The holder idiom gets the same guarantees with none of the subtlety.

**The honest caveats:**

- If the constructor throws → `ExceptionInInitializerError`, the class is permanently
  marked erroneous, and every later call throws `NoClassDefFoundError`. No retry.
- Not immune to reflection (`setAccessible(true)`) or serialization. **Enum singleton
  is** — that's Effective Java's actual recommendation.
- Recursive `getInstance()` from inside the constructor does *not* block (same thread)
  and can observe a partially-initialized class.

**Why Singleton ages badly:**

- Hidden global dependency — the dependency doesn't appear in constructors, so DIP is violated
- Untestable — no way to inject a fake
- **Breaks across JVMs** — three app servers means three "singletons"; the "one
  instance" claim was never true
- A no-arg constructor **cannot accept dependencies**, which forces you to either
  hardcode them or add mutable setters (and setters reintroduce races)

**If you must keep it and still want `final` fields** — configure-then-get:

```java
private static volatile AllocationStrategy initAllocation;
public static void configure(AllocationStrategy a, FeeStrategy f) { ... }

private final AllocationStrategy strategy;   // FINAL — race gone by construction
private ParkingLot() {
    this.strategy = Objects.requireNonNull(initAllocation, "call configure() first");
}
```

**Interview line:**
> "Holder idiom — lazy because the JVM defers class initialization until first use,
> thread-safe because `<clinit>` runs once under the JVM's init lock. I'd normally
> skip the singleton though: there can be more than one parking lot, and global
> state is hard to test."

### Composite (brief)

A tree where a branch and a leaf look identical to the calling code.

```java
interface Item { int size(); }
class File   implements Item { public int size() { return bytes; } }        // LEAF
class Folder implements Item {                                             // COMPOSITE
    private final List<Item> children = new ArrayList<>();
    void add(Item i) { children.add(i); }                // takes Item, so folders nest
    public int size() {
        int total = 0;
        for (Item c : children) total += c.size();       // recursion
        return total;
    }
}
```

**Tell that you need it:** you're writing `if (x instanceof Folder) { loop } else { single }`.

---

## 4. Java Type System for Design

### Records

A **nominal tuple** — a transparent carrier for a fixed set of values.

```java
record SeatId(String row, int number) {
    SeatId {                                        // compact constructor
        if (number < 1) throw new IllegalArgumentException();
        row = row.toUpperCase();                    // assigns to the PARAMETER
    }                                               // implicit: this.row = row;
}
```

You get: final fields, canonical constructor, accessors `row()`/`number()` (no `get`
prefix), and component-based `equals`/`hashCode`/`toString`.

**Internals:**
- The class is `final` and extends `java.lang.Record` (which is why it can't extend
  anything else — the slot is taken)
- `ACC_RECORD` flag set; a `Record` attribute lists each component's name and type
  — this is what makes records reflectively transparent (`Class.getRecordComponents()`)
- `equals`/`hashCode`/`toString` are **not** ordinary bytecode. They're
  `invokedynamic` calls bootstrapped by `java.lang.runtime.ObjectMethods`, which
  builds a `MethodHandle` chain over the accessors at first call. Keeps class files
  small and lets the JDK change semantics without recompilation.

**Three gotchas:**

1. **Shallow immutability.** The *reference* is final; the object it points to isn't.
   ```java
   record Booking(String id, List<SeatId> seats) {
       Booking { seats = List.copyOf(seats); }      // required
   }
   ```
   Without this, whoever passed the list in can still mutate your "immutable" record
   — *and* the final-field freeze doesn't protect the contents, so it isn't safe to
   publish across threads either.

2. **Arrays break `equals`.** Generated `equals` uses `Objects.equals` per component,
   which for arrays is *reference* equality. Use `List` instead.

3. **Free safe publication** — provided you did (1).

**Where to use:** value objects, compound map keys, multiple return values, DTOs and
events, sealed-hierarchy variants.

**Where not:** anything with identity, mutable state, derived/cached fields, or a
class you need to extend.

**Compound map keys are underused:**
```java
record SpotKey(int floor, VehicleType type) {}
Map<SpotKey, List<Spot>> index = new HashMap<>();   // correct key for free
```

**Sealed + records = sum types:**
```java
sealed interface PaymentResult permits Success, Declined, Pending {}
record Success(String txnId) implements PaymentResult {}
record Declined(String reason) implements PaymentResult {}

String describe(PaymentResult r) {
    return switch (r) {                              // exhaustive — compiler-checked
        case Success(String id)   -> "ok: " + id;    // record pattern (Java 21)
        case Declined(String why) -> "declined: " + why;
        case Pending(Instant t)   -> "retry at " + t;
    };
}
```

### `final` — three different meanings

| Placement | Meaning |
|---|---|
| `final class` | cannot be subclassed |
| `final method` | cannot be overridden |
| `final field` | assign once, never reassign |
| `final` local | cannot be reassigned (compile-time only) |

**Why `final class` matters — immutability isn't safe without it:**

```java
class Money {                              // NOT final
    private final long amount;
    public long amount() { return amount; }
}
class SneakyMoney extends Money {
    private long extra;
    @Override public long amount() { return super.amount() + extra++; }   // mutable now
}
```

Anything holding a `Money` reference thought it had an immutable value. It doesn't.
`String` is final precisely to prevent this.

Also: `equals` symmetry (`getClass()` vs `instanceof` — final sidesteps the whole
problem), and the Effective Java rule: *design and document for inheritance, or
prohibit it.*

**`final method` on template methods** tells the subclass author "override the hook,
not the algorithm":

```java
abstract class PrintService {
    protected abstract Printer acquirePrinter();     // the hook — override this
    public final void print(Document d) { ... }      // the algorithm — closed
}
```

This matters more than style: making `print()` final means nobody can override it and
skip the `finally` that returns the printer to the pool.

**Performance:** `final class` and `final method` do **not** speed anything up.
HotSpot's class hierarchy analysis already inlines monomorphic call sites and guards
them for deoptimization. It doesn't need your hint. `final` **fields** are different —
they enable constant folding and the JMM freeze.

**`final` on a collection field:**
```java
private final List<Spot> spots = new ArrayList<>();
spots.add(x);                       // fine — mutating the object
spots = new ArrayList<>();          // compile error — reassigning the variable
```
`final` locks the arrow, not the box. Standard and correct for `ParkingLot.floors` —
you *want* the contents mutable there.

**`List.copyOf` vs the alternatives:**

```java
new ArrayList<>(src)                 // copy, but MUTABLE
Collections.unmodifiableList(src)    // a VIEW — changes to src still show through!
List.copyOf(src)                     // SNAPSHOT + frozen  ← what you want
```

`copyOf` does two things: **copies** (cuts the link to whoever gave you the
collection) and **freezes** (so your accessor can't hand out something mutable).
It's null-hostile, and it returns the same instance if the input is already
immutable, so copying an immutable list is free. Still shallow — mutable *elements*
remain mutable.

### Access modifiers

| Modifier | Visible to |
|---|---|
| `private` | same class (plus nestmates) |
| *(none)* | same package — **the default** |
| `protected` | same package **+ subclasses anywhere** (wider, not narrower) |
| `public` | everyone |

Enforced at two layers: `javac` rejects it, and the **JVM verifier** rejects it again
at link time. Only escape is reflection with `setAccessible(true)`.

**Use them to express design:**
```java
public final class ParkingLot {
    private final List<Floor> floors;          // internal state
    public  Ticket park(Vehicle v) { ... }     // the API
            Spot findSpot(VehicleType t) { }   // package-private: tests + collaborators
}
abstract class PrintService {
    protected abstract Printer acquire();      // extension hook
    public final void print(Document d) { }    // closed algorithm
}
```

OCP made visible: which parts are open and which are closed is legible from the
modifiers alone.

### Functional interfaces

One question: **does it take input, does it return output?**

| Interface | Signature | Method |
|---|---|---|
| `Supplier<T>` | `() -> T` | `get()` |
| `Consumer<T>` | `(T) -> void` | `accept()` |
| `Function<T,R>` | `(T) -> R` | `apply()` |
| `Predicate<T>` | `(T) -> boolean` | `test()` |
| `Runnable` | `() -> void` | `run()` |

`default` and `static` methods don't count toward "one abstract method" — which is
why `Comparator` has dozens and is still functional. Neither do public methods of
`Object`.

Primitive specializations (`IntFunction`, `ToIntFunction`, `IntPredicate`, …) exist
purely to avoid boxing — that's why `java.util.function` has 43 interfaces.

**Why `computeIfAbsent` takes a `Function`:** it hands you the key and wants a value
back. `Optional.orElseGet` takes a `Supplier` — nothing to hand you.

**Any single-abstract-method interface is functional, including yours:**
```java
interface PricingStrategy { double price(Duration d); }
PricingStrategy hourly = d -> d.toHours() * 20;      // no class needed
```
Handy for a quick `main()` demo. Still write named classes for strategies you're
graded on — the class name communicates intent that a lambda doesn't.

### Collections — from a C++ background

| C++ STL | Java | Note |
|---|---|---|
| `vector<T>` | `ArrayList<T>` | |
| `unordered_map<K,V>` | `HashMap<K,V>` | default choice |
| `map<K,V>` | `TreeMap<K,V>` | red-black tree, sorted |
| `deque<T>` | `ArrayDeque<T>` | **not** indexable |
| `stack<T>` / `queue<T>` | `ArrayDeque<T>` | avoid legacy `Stack` |
| `priority_queue<T>` | `PriorityQueue<T>` | **MIN-heap by default** |
| `lower_bound` | `TreeMap.ceilingKey()` | plus `floorKey`, `higherKey`, `lowerKey` |
| `pair<A,B>` | `record Pair<A,B>(A a, B b)` | no built-in |

**Five things that bite:**

1. **`PriorityQueue` is a min-heap.** C++ defaults to max. Use
   `Comparator.reverseOrder()` for C++ behaviour.
2. **No `operator[]` and no default-construction on maps.** `m[k]` in C++ inserts;
   Java's `get()` returns `null`. That's what `computeIfAbsent` and `getOrDefault` fix.
3. **Everything is a reference. No value semantics.** Assignment never copies.
4. **Generics can't hold primitives.** `List<Integer>` with autoboxing — and `==` on
   boxed types compares *references* outside the −128..127 cache. Always `.equals()`.
5. **`equals`/`hashCode` replace `operator==` and `std::hash`.** Miss them and your
   `HashMap` silently fails to find what you just inserted.

**The seven idioms that cover ~80% of LLD problems:**
```java
map.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
map.merge(k, 1, Integer::sum);
map.getOrDefault(k, 0);
treeMap.floorKey(10);  treeMap.ceilingKey(10);
new PriorityQueue<>(Comparator.comparingInt(X::val));
deque.push(x); deque.pop(); deque.peek();
concurrentMap.putIfAbsent(k, v);
```

### Streams (concept level)

A stream is **not a data structure** — no storage, no source mutation. It's a
pipeline description: source → intermediate ops (lazy) → one terminal op (triggers).

**Sink fusion — the key mental model.** Elements flow one at a time through the
*entire* chain, not layer by layer:

```
NOT:  filter everything → list → map everything → list → print
BUT:  e1 → filter → map → print
      e2 → filter → map → print
      e3 → filter → (rejected, next)
```

One pass, no intermediate collections. That's why chaining ten operations doesn't
cost ten traversals.

**Stateful ops are barriers.** `sorted` must consume every element before emitting
any. `distinct`, `limit`, `skip` also buffer. `map`/`filter`/`peek` are stateless
and fuse cleanly.

**`Spliterator`** is the foundation — `tryAdvance`, `trySplit` (enables parallelism),
`estimateSize`, and characteristics (`SIZED`, `SORTED`, `DISTINCT`, …) that drive
optimizations.

**Parallel streams — three real hazards:**
1. The common `ForkJoinPool` is **JVM-wide**. Block on I/O inside a parallel stream
   and you starve every other parallel stream in the process.
2. Reduction must be **associative**.
3. `forEach` gives no ordering guarantee.

Worth it around 10k+ elements with real per-element CPU work. Below that, fork/join
overhead dominates.

**Gotchas:** `peek` may be skipped entirely (since Java 9, `count()` on a `SIZED`
source can return the size without traversing). `Collectors.toList()` is mutable,
`Stream.toList()` (16+) is not. `Collectors.toMap` throws on duplicate keys unless
you supply a merge function.

---

## 5. How a Java Program Actually Runs

```
  Main.java
     │  javac ── lex, parse, type-check, desugar
     ▼
  Main.class  (0xCAFEBABE, constant pool, methods)
     │  java  ── launcher starts the JVM
     ▼
┌──────────────── JVM ───────────────────────────────────┐
│  CLASS LOADER                                          │
│    Loading   → bootstrap → platform → application      │
│    Linking   → verify → prepare → resolve              │
│    Init      → <clinit> runs (lazy, thread-safe)       │
│         ▼                                              │
│  RUNTIME DATA AREAS                                    │
│    shared:     Heap │ Metaspace │ Code Cache           │
│    per-thread: PC   │ JVM Stack │ Native Stack         │
│         ▼                                              │
│  EXECUTION ENGINE                                      │
│    Interpreter ──profile──▶ C1 ──hotter──▶ C2          │
│         ▲                              │               │
│         └───────── deoptimization ─────┘               │
│    Garbage Collector                                   │
└────────────────────────────────────────────────────────┘
```

### Stage 1 — `javac` produces bytecode, not machine code

It lexes, parses, type-checks, and **desugars**. Things that vanish here:

- **Generics** → erased. `List<String>` becomes `List` + casts. This is why you can't
  do `new T[]`, and why `List<String>` and `List<Integer>` are the same runtime class.
- **Lambdas** → `invokedynamic`. **Not** anonymous inner classes; no extra `.class`
  file is generated.
- **Enhanced for** → iterator calls. **Autoboxing** → `Integer.valueOf()`.
- **String concat** → `invokedynamic` via `StringConcatFactory`.

`javac` does **almost no optimization** — deliberately. That's the JIT's job, because
the JIT has runtime information the compiler can't have.

### Stage 2 — Class loading, in phases

This is the stage most people skip, and it has a direct LLD consequence.

**Loading** — read bytes, parse, build the `Klass` in **Metaspace** (native memory),
create a `java.lang.Class` mirror on the **heap**.

**Linking:**
- *Verification* — the bytecode verifier proves type safety before anything runs: no
  operand-stack over/underflow, valid jump targets, no illegal casts.
- *Preparation* — static fields get **default** values (`0`, `null`, `false`). Not
  your assigned values. Storage lives in the heap mirror (pre-Java 8 it was PermGen).
- *Resolution* — symbolic constant-pool references → direct references. Lazy in HotSpot.

**Initialization** — now `<clinit>` runs: static blocks and static field assignments,
in source order.

> **Critical distinction: static fields are assigned at INITIALIZATION, not at
> LOADING.** After loading, `Holder.INSTANCE` exists and is `null`. Initialization is
> triggered lazily, on first *active use* of that specific class — and every class
> initializes independently. There is no global "statics get set up at startup" moment.

**Exception:** `static final` with a *compile-time constant* (`static final int MAX = 100`)
is inlined by `javac` and triggers no initialization at all. This is why the holder
idiom needs an **object** — a constant would be inlined and the laziness would vanish.

**Verify it in 30 seconds:** put a print in `Holder`'s static block and one at the top
of `main()`. `main` prints first.

### Stage 3 — Runtime data areas

**Shared:** Heap (all objects/arrays), Metaspace (class metadata, bytecode, constant
pool — native memory), Code Cache (JIT output).

**Per-thread:** PC register; **JVM stack** (a stack of frames — each with a local
variable array, an operand stack, and frame data); native method stack.

> **LLD payoff:** each thread has its own stack, so **local variables are inherently
> thread-safe.** Only heap state — fields, statics, shared objects — needs
> synchronization. This single fact is the foundation of every decision about what to
> lock, and it's why a stateless Strategy object can be shared across threads.

Also: the JVM stack is a **GC root**. Locals in active frames keep heap objects alive.

### Stage 4 — Execution: interpret, then compile

HotSpot is hybrid. Bytecode starts **interpreted** (fast startup, slow steady state)
while invocation counters and loop back-edge counters accumulate.

**Tiered compilation:** C1 compiles fast with light optimization and keeps profiling;
C2 kicks in for genuinely hot code with aggressive optimization — **inlining** (the
big one), escape analysis, loop unrolling, dead code elimination.

C2's edge is **speculative optimization** on real profile data. If a call site has
only ever seen one implementation, C2 inlines it directly behind a cheap type guard.
A new type later ⇒ guard fails ⇒ **deoptimization** back to the interpreter, recompile.

> **LLD payoff:** "won't all those interfaces and small classes be slow?" is the wrong
> instinct. Monomorphic and bimorphic call sites get inlined away. Design for clarity.

**OSR** (on-stack replacement) compiles long-running loops mid-flight.

### `invokedynamic`

**The one JVM instruction that lets the program decide, at runtime, what a call site
calls — and then remembers the answer.**

The other four (`invokestatic`, `invokespecial`, `invokevirtual`, `invokeinterface`)
all hardcode the target at compile time.

**How it works — first execution only:**
1. The JVM calls a named **bootstrap method** (ordinary Java code), passing a
   `Lookup`, the call site name, and its `MethodType`
2. The bootstrap returns a **`CallSite`** holding a `MethodHandle`
3. The JVM **links** it permanently

Every execution afterward invokes the linked handle directly. That's why it's fast:
one-time cost, and afterwards the JIT treats it like an ordinary call — **inlinable
and speculatively optimizable**. This is the crucial difference from reflection,
which the JIT largely can't see through.

**Where you meet it:** lambdas (`LambdaMetafactory` spins a class in memory), records
(`ObjectMethods` builds the accessor chain), string concat (`StringConcatFactory`),
switch pattern matching (`SwitchBootstraps`).

**Verify:** `javap -c -p` shows the `invokedynamic`; `javap -v` shows the
`BootstrapMethods` table.

### Tools

| Tool | Does |
|---|---|
| `javac` | compiles `.java` → `.class` |
| `java` | launches the JVM; since 11 also runs a single source file directly |
| `jshell` | REPL — test a pattern in 30 seconds |
| `javap -c -p` | **disassembles bytecode** — see what your lambda desugared into |
| `jcmd` | modern diagnostics front door |
| `jstat` / `jmap` / `jstack` | GC stats / heap dump / thread dump |
| `jlink` / `jpackage` | trimmed runtime image / native installer |

---

## 6. Memory Management

> Not tested in machine coding rounds. Shows up in theory follow-ups and senior
> discussion. Bounded section.

### Layout

- **Stack**: primitives and *references*. Per-thread, method-local.
- **Heap**: every object and array. Where GC operates.
- **Metaspace**: class metadata, in native memory. Replaced PermGen in Java 8 — if a
  resource mentions PermGen, it's stale.
- Heap generations: young (Eden + two survivor spaces) and old.
- Minor GC (young only) vs full GC (whole heap).

**Java is always pass-by-value.** Object references are values that get copied. This
is the most commonly wrong answer to a Java memory question.

### Reachability

Objects aren't collected by reference counting — they're collected by being
**unreachable from GC roots**. This is the mental model that makes leaks make sense.

`java.lang.ref`: strong → soft → weak → phantom. Weak references have a real LLD use
(see below).

`finalize()` is deprecated for removal. Use try-with-resources / `AutoCloseable`, or
`Cleaner` for native resources.

### Collectors — name-level knowledge is enough

- **G1** — default since Java 9. Splits the heap into equal-sized regions, collects
  the ones holding most garbage first, targets predictable pause times.
- **ZGC** — sub-millisecond pauses regardless of heap size, via colored pointers and
  load barriers; nearly all work concurrent. Java 25 ships Generational ZGC as the
  only form. **Still opt-in — G1 remains the default.**
- **Serial** for tiny heaps, **Parallel** for batch throughput.

### The counterintuitive fact worth carrying

> **Allocation cost scales with the size of the live set, not the volume of garbage.**

A generational collector *copies survivors*; dead objects cost nothing to reclaim.
This is why "allocating lots of short-lived objects is expensive" is usually wrong,
and why **pooling cheap objects is a well-known anti-pattern on the JVM** — pooling
makes objects survive longer, promotes them to old gen, and creates work for the
collector, plus adds leak risk from unreturned objects.

### Where memory management intersects LLD

This is the part actually worth attention — a memory question can surface *inside* a
design round:

- **Observer** — listeners registered and never deregistered are the textbook Java
  leak. Provide explicit `unsubscribe()`, or hold listeners weakly. Mentioning this
  unprompted while designing an Observer is a genuine differentiator.
- **Singleton** — lives for the JVM's lifetime, so anything it strongly references is
  immortal. A static `Map` cache with no eviction is a leak by construction. (Static
  fields live in the `Class` mirror, which is reachable from a GC root while the class
  is loaded.)
- **Caches** — this is *why* your LRU cache needs bounded capacity and TTL.
- **ThreadLocal in thread pools** — pooled threads never die, so the entry never
  clears. Always `remove()` in a `finally`.
- **Non-static inner classes** hold an implicit outer reference — can keep a large
  object alive unexpectedly. (This is a second reason Builder's nested class is static.)

**Skip:** GC tuning flags, escape analysis internals, JVMTI, heap dump analysis, JFR.

---

## 7. The Java Memory Model

### The three problems

Everything in concurrency exists to fix one of three things. **Conflating them is
where most confusion lives.**

1. **Visibility** — thread A writes, thread B never sees it (value sat in a register
   or store buffer)
2. **Reordering** — compiler, JIT, and CPU all reorder freely; single-threaded
   semantics are preserved, cross-thread ones aren't
3. **Atomicity** — `count++` is read-modify-write, three operations, interleavable

`volatile` fixes 1 and 2. `synchronized` and locks fix **all three**.

### Happens-before

> **"A happens-before B" means everything A wrote is guaranteed visible to B. It does
> NOT mean A ran earlier on a clock.**

Two actions can be ordered in wall-clock time with no happens-before edge — and then
B may not see A's writes at all.

**The edges:**

| Edge | Meaning |
|---|---|
| Program order | within one thread, earlier statements → later ones |
| Monitor | unlock → any *subsequent* lock of the **same** monitor |
| Volatile | write to a volatile field → every subsequent read of it |
| Thread start | `t.start()` → everything inside `t` |
| Thread join | everything in `t` → `t.join()` returning |
| Final fields | the constructor freeze (see below) |

**It's transitive.** A → B and B → C gives A → C.

### The piggyback effect

A volatile write doesn't just publish that one field — **everything the thread wrote
before it becomes visible** to a thread that subsequently reads that volatile. The
volatile access is a gate; all prior writes come through with it.

```java
private volatile boolean initialized = false;
private Config config;                        // NOT volatile

void init() {
    config = loadConfig();                    // ordinary write
    initialized = true;                       // volatile write — publishes config too
}
void use() {
    if (initialized) {                        // volatile read
        config.get(...);                      // guaranteed to see the fully-built config
    }
}
```

This is also why double-checked locking needs `volatile` on the instance field:
without it, another thread can see a non-null reference to a Singleton whose
constructor hasn't finished.

### Final field semantics (JLS 17.5)

> If an object's fields are all `final`, and `this` doesn't escape during
> construction, then **any thread that obtains a reference sees the fully initialized
> final fields** — with no synchronization at all.

A freeze action at the end of the constructor prevents the JVM and CPU from
reordering field writes after reference publication.

Non-final fields get **no such guarantee** — a thread can see a non-null reference
alongside `null`/`0` field values.

**The `this`-escape hazard kills it:**
```java
private Pizza(Builder b) {
    this.size = b.size;
    REGISTRY.add(this);        // BUG: escapes before construction completes
}
```

**This is the mechanism behind:** immutable objects being freely shareable, Builder +
final fields being safe to publish, and records being thread-safe for free.

### What `volatile` costs

Memory barriers, and the cost is architecture-dependent:

- **x86** (strong model, TSO): a volatile *read* costs essentially nothing — the
  hardware already gives you the ordering, only the compiler needs restraining. A
  volatile *write* compiles to a locked instruction used as a full fence.
- **ARM** (weakly ordered): both directions need explicit `dmb` barriers.

So "volatile is cheap" is an x86 statement.

**What volatile does NOT do: atomicity.** `volatileCounter++` is still broken.

**And a critical subtlety:** `volatile` on a *reference* protects the reference, not
the object. `private volatile Truck shared` guarantees you see the latest pointer,
and guarantees **nothing** about `shared.currentLocation`. Volatile-ness does not
reach through the reference.

---

## 8. Synchronization Primitives

### `synchronized`

**Bytecode:** a synchronized *block* compiles to `monitorenter`/`monitorexit`, plus a
second `monitorexit` in a compiler-generated exception handler — so the lock releases
even on a throw. A synchronized *method* has no such instructions; it carries the
`ACC_SYNCHRONIZED` flag and the JVM handles the monitor at invocation.

**Internals — the mark word and lock inflation.** Every object header has a mark word
encoding lock state. HotSpot escalates:

- **Lightweight (thin) lock** — uncontended. The thread CASes a pointer to a lock
  record on its own stack into the mark word. No OS involvement, tens of nanoseconds.
  **This is the common case, which is why "synchronized is slow" is outdated folklore.**
- **Heavyweight (inflated)** — under contention the lock inflates to a full
  `ObjectMonitor` with wait/entry queues; blocked threads **park** via the OS (futex
  on Linux). Context switches. This is where the real cost lives.

**Biased locking** was the historical third tier — deprecated in JDK 15, **removed in
JDK 18**. Plenty of blog posts still describe it as current.

**Reentrancy** is tracked by a recursion count in the lock record. Without it, any
synchronized method calling another on the same object would self-deadlock.

*Catch:* reentrancy means an overridden method can re-enter the lock while your object
is half-updated — inconsistent view, no deadlock to warn you. Part of why template
methods should be `final`.

**What goes in the brackets:** the object whose monitor you're taking. Any object
works. **Two threads are mutually excluded only if they name the same object.**

```java
synchronized (this)              // this instance's monitor
synchronized (ParkingLot.class)  // the Class object's — ONE per JVM, all instances share
synchronized (lock)              // a private field's monitor  ← preferred
```

**Why a private lock object:** `this` is public, so its monitor is a public resource.
Any code holding your `ParkingLot` can write `synchronized(lot) { Thread.sleep(60_000); }`
and freeze your class. They never touched your fields. You can't prevent or detect it.
A private `Object` field has no reference outside your class.

Same reasoning for subclasses (they'd share your monitor) and for future lock
splitting.

*It buys encapsulation, not performance* — a private lock and `this` are both one
monitor, identical cost.

### `ReentrantLock` and AQS

Not a JVM primitive — **library code in Java**, built on `AbstractQueuedSynchronizer`.

**AQS internals:** a `volatile int state` plus a FIFO queue of waiting threads
(CLH-variant linked list).

- **Acquire:** CAS `state` 0→1. Won? You own it, no OS involvement — same fast path as
  a thin lock. Lost? Enqueue as a node and park via `LockSupport.park()` (futex).
- **Release:** set `state` to 0, unpark the successor.

**The unification worth remembering:** that `state` field is `volatile`. The
happens-before edge a `ReentrantLock` gives you comes from *exactly the same
mechanism* as `volatile`. `synchronized` and `ReentrantLock` differ in **where the
queueing logic lives** (C++ in the JVM vs Java in the library), not in the underlying
primitives. Everything bottoms out in **CAS + memory barriers**.

**Fairness:** unfair (default) lets an arriving thread barge — higher throughput,
possible starvation. Fair checks the queue first — no starvation, meaningfully slower.

### Choosing

| Need | Use |
|---|---|
| Publish a flag or reference; no compound update | `volatile` |
| Guard a small critical section | `synchronized` |
| `tryLock()`, timeout, interruptible acquire | `ReentrantLock` |
| Multiple wait sets (notFull / notEmpty) | `ReentrantLock` + `Condition` |
| Fairness guarantee | `ReentrantLock(true)` |
| Single-variable counter or flag | `AtomicInteger` / `AtomicReference` |
| Many readers, few writers | `ReadWriteLock` / `StampedLock` |

**Default to `synchronized`.** It can't leak — the compiler-generated handler releases
on exception — while `ReentrantLock` demands `try/finally` and a forgotten `unlock()`
hangs your application permanently.

**JIT behaviour worth naming:** **lock elision** (escape analysis proves an object
never escapes a thread ⇒ locks removed entirely) and **lock coarsening** (adjacent
blocks on the same monitor merged). This is why `StringBuffer` in a local variable
costs roughly what `StringBuilder` costs.

### The primitives map

| Primitive | Use it when |
|---|---|
| `synchronized` | guard a compound operation |
| `AtomicLong` + CAS loop | single-variable update, no lock |
| `ReadWriteLock` | many reads, rare writes |
| `BlockingQueue` | hand work between threads (producer-consumer) |
| `ExecutorService` | run N tasks on a pool |
| `CountDownLatch` | wait for N things to finish (one-shot) |
| `CyclicBarrier` | N threads wait for each other (reusable) |
| `Semaphore` | bound access to N resources |
| `ScheduledExecutorService` | expire something after a timeout |
| `ConcurrentHashMap` | concurrent map with atomic compound ops |

### `CountDownLatch` in detail

A counter threads can wait on. Three methods: `countDown()`, `await()`, and the
constructor. **One-shot** — once at zero it stays there.

**Two distinct patterns:**

```java
// 1. START GATE — one thread releases many. Count of 1.
CountDownLatch startGate = new CountDownLatch(1);
// each worker: startGate.await();      ← 30 threads park here
startGate.countDown();                  // ← all 30 released simultaneously
```
Without this, thread 1 starts while thread 30 is still being created — they never
overlap and **your race never fires.** This is why a concurrency test can detect a bug
at all.

```java
// 2. COMPLETION GATE — many threads release one. Count of N.
CountDownLatch done = new CountDownLatch(30);
// each worker, in finally: done.countDown();
done.await();                           // main blocks until all finish
```
`join()` does something similar, but only for threads you directly own — with an
`ExecutorService` you don't have the `Thread` objects.

Internally an AQS subclass where `state` is the count.

### Thread-safe data structures ≠ thread-safe operations

```java
ConcurrentHashMap<SeatId, UserId> booked = new ConcurrentHashMap<>();

boolean book(SeatId seat, UserId user) {
    if (!booked.containsKey(seat)) {   // A — atomic
        booked.put(seat, user);        // B — atomic
        return true;                   // ...and yet both threads get seat 14
    }
    return false;
}
```

Every individual operation is thread-safe. **The gap between A and B is not.**

```java
return booked.putIfAbsent(seat, user) == null;   // atomic check-and-set
```

> **A thread-safe data structure protects its own contents, not your multi-step
> operation.**

### Virtual threads — the common misconception

They are **not** what gives Java CPU parallelism. Platform threads always did that.

Virtual threads solve a *different* problem: a million concurrent tasks without a
million OS threads. A virtual thread **mounts** onto a carrier thread; when it blocks
on I/O it **unmounts** (stack copied to the heap), freeing the carrier.

For CPU-bound work a virtual thread never unmounts — no benefit, no penalty.

> **Virtual threads are for scaling concurrency, not for creating parallelism.**

(In Java 21, blocking inside `synchronized` *pinned* a virtual thread to its carrier.
JEP 491 fixed this in JDK 24 — many blog posts still warn about it.)

---

## 9. The Universal Concurrency Bug: Check-Then-Act

This one pattern showed up **four separate times** in different clothes. Recognising
it is worth more than any individual primitive.

### The shape

```
    read some shared state          ← CHECK
    ──── gap ────                   ← another thread can act here
    write based on what you read    ← ACT
```

### The four costumes

| Domain | Check | Act | Symptom |
|---|---|---|---|
| Seat booking | `containsKey(seat)` | `put(seat, user)` | two users, one seat |
| Truck dispatch | `if (!occupied)` | `occupied = true` | one truck, two deliveries |
| Parking spot | `findParkingSpot()` | `spot.parkVehicle()` | 30 cars, one spot |
| Database row | `SELECT WHERE status='FREE'` | `UPDATE SET status='TAKEN'` | lost update |

**Same bug. Same reasoning. The layer changes, the shape doesn't.**

### The three fixes

**1. Lock across both steps** — make the compound operation atomic:
```java
synchronized (lock) {
    spot = strategy.findSpot(floors, vehicle);
    spot.parkVehicle(vehicle);          // find AND claim, one critical section
}
```
Splitting these across two synchronized *methods* does nothing — the gap survives.

**2. Make the claim atomic** — CAS, no lock:
```java
private final AtomicBoolean occupied = new AtomicBoolean(false);
boolean tryPark(Vehicle v) {
    if (!occupied.compareAndSet(false, true)) return false;   // lost the race
    this.vehicle = v;
    return true;
}
```
The strategy returns *candidates*; the caller walks them until a claim succeeds.
Losers just try the next one. Threads only contend when they pick the same spot.

**3. Pool checkout** — make acquisition itself the claim:
```java
Transport t = fleet.take();             // BlockingQueue — blocking IS the domain model
```

### Where the invariant lives determines the fix

| Authority | Mechanism |
|---|---|
| One JVM | `BlockingQueue` / `ConcurrentHashMap.putIfAbsent` / `synchronized` |
| Database | Conditional `UPDATE ... WHERE status='FREE'`, `@Version`, `SELECT ... FOR UPDATE` |
| Multiple services | Distributed lock (Redis) or a DB row as arbiter |

Every row is the same pattern: **make check-and-claim one atomic step at the
authority.**

```sql
-- putIfAbsent, written in SQL. Rows-affected decides the winner.
UPDATE trucks SET status='ASSIGNED' WHERE id=47 AND status='AVAILABLE';
```

### The distinction that catches people out

> **Thread-safe objects and a safe resource are different claims.**

Your `Truck` objects can be perfectly thread-confined while the physical truck gets
double-booked. Object-level safety is about **memory**; resource-level safety is about
the **invariant** — and the invariant lives wherever the authority is.

**Ask: "who owns the truth?" Synchronize there.**

### Lock at the level of the invariant

```java
// ParkingSpot.parkVehicle — NO synchronized here
```

Why not:
1. It buys nothing — every caller already holds the lot's lock
2. It **actively misleads** — a reader sees `synchronized parkVehicle` and concludes
   "safe to call standalone." It isn't: called outside the lot's lock you're back to
   check-then-act.

> **Synchronization belongs at the level of the invariant, not on every method that
> touches shared state.**

The invariant is "one spot, one vehicle" — it spans find *and* claim, so the lock
lives at `ParkingLot.parkVehicle`. Inner methods are participants, not boundaries.

Sprinkling `synchronized` everywhere is the classic over-locking mistake: code that
*looks* thread-safe, isn't, and is slower. `Vector` and `Hashtable` are the canonical
examples — every method synchronized, still useless for compound operations.

### You cannot lock on a non-canonical object

If `Seat` objects are created freely per request, `synchronized(seat)` provides **zero
mutual exclusion** — different instances, different monitors, both threads enter.

**You can only lock on a shared instance.** The moment you decide identity doesn't
live in the heap, you give up the ability to use those objects as locks.

Two more reasons to guard the registry rather than the individual objects:
- **Multi-item deadlock**: booking {14,15} and {15,14} concurrently deadlocks unless
  you impose a global lock ordering forever
- **No atomic commit point**: booking three seats is all-or-nothing; per-object locks
  force manual unwinding

> **Lock the thing that owns the invariant, not the things the invariant is about.**

---

## 10. Testing Concurrent Code

### The core rule

> **A passing concurrency test proves nothing. Races are intermittent; a test that
> merely runs without crashing has told you nothing.**

Every test needs an **invariant assertion.**

### Test design

```java
// 1. TINY resource count + LARGE thread count → maximum contention
final int THREADS = 30, SPOTS = 5;

// 2. START GATE → all threads fire simultaneously
CountDownLatch startGate = new CountDownLatch(1);
// worker: startGate.await();
startGate.countDown();

// 3. COMPLETION GATE → assert only after everyone finishes
CountDownLatch done = new CountDownLatch(THREADS);
done.await();

// 4. THE ASSERTION — this is the whole point
Set<String> used = new HashSet<>();
for (Ticket t : issued) {
    if (!used.add(t.getParkingSpot().getSpotId())) duplicates.add(...);
}
assert issued.size() == SPOTS && duplicates.isEmpty();
```

### Widening the window — the technique that made it visible

The race in `ParkingSpot` lived between `if (isOccupied)` and `isOccupied = true` —
about three bytecodes, a couple of nanoseconds. Two threads must *both* read before
either writes. With thread-start jitter in microseconds, you'd run thousands of times
before seeing it.

**So force it:**

```java
public boolean parkVehicle(Vehicle vehicle) {
    if (isOccupied) return false;
    try { Thread.sleep(100); } catch (InterruptedException e) {}   // ← widen the window
    this.vehicle = vehicle;
    this.isOccupied = true;
    return true;
}
```

**Result: 30 tickets issued, all for spot M-5.** 100% reproducible.

Then put the lock back **with the sleep still in place** and confirm you get exactly
5 on 5 distinct spots. That proves the lock *closes* the window rather than merely
narrowing it.

### A second line of defence is not a fix

The `if (isOccupied)` check inside `ParkingSpot` made the unlocked version *look*
correct — it rejected 25 of 30 threads and the test passed. That check wasn't a guard;
it was a *narrow window*.

> **A narrow race is still a race. It fires under load, on different hardware, with a
> different core count — in production, at 2am.**

### Other notes

- Comment out domain `println`s for tests — they drown the signal and the I/O
  serializes threads, which itself hides races
- Run the test ~20 times; a single green run means little
- Test 2 shape: churn (park/unpark repeatedly), then black-box check that all N
  resources are reclaimable. Catches leaks and double-frees.

---

## 11. Case Study: Parking Lot

### Final structure

```
ParkingLot        — singleton, holds floors + activeTickets, owns the lock
ParkingFloor      — Map<String, ParkingSpot>
ParkingSpot       — entity: spotId, spotSize, isOccupied, vehicle
Ticket            — entity: ticketId, spot, vehicle, entryTime, exitTime
Vehicle           — licensePlate, VehicleSize
ParkingSpotAllocationStrategy  ← interface (NearestFirst, …)
FeeCalculationStrategy         ← interface (SizeBased, FlatRate, …)
```

### The bugs found, in order

| # | Bug | Symptom | Fix |
|---|---|---|---|
| 1 | `activeTickets` never initialized | NPE on first park | init in constructor |
| 2 | `isOccupied` was `Boolean`, never set | NPE on unboxing | `boolean isOccupied = false` |
| 3 | `unpark` used `get()` not `remove()` | ticket leaks; plate can never re-park | `remove()` — atomic, one winner |
| 4 | `unpark` had no lock | double-free, double-charge | same lock as `park` |
| 5 | `park`/`unpark` used *different* locks | no mutual exclusion at all | one lock object, both methods |
| 6 | find + claim not atomic | **30 cars in one spot** | one `synchronized` block |
| 7 | `Duration.toHours()` truncates | every fee was 0.0 | `Math.max(1, ceil(minutes/60))` |
| 8 | `System.out` in domain methods | untestable, drowns test output | return values, print in `main()` |

### Design decisions worth defending out loud

**Fee strategy stored on the Ticket, not read from the lot at exit:**
```java
Ticket ticket = new Ticket(spot, vehicle, feePolicy.strategyFor(vehicle));
// at exit:
double fees = ticket.getFeeStrategy().calculateFee(ticket);
```
Two wins: the rate quoted at entry is honoured at exit (how real parking, hotels, and
insurance actually work), **and** the race on a mutable `feeStrategy` field disappears
— not because you locked it, but because the shared mutable field is no longer on the
read path.

Store the **strategy object**, not a name — a name forces a lookup, and a lookup is a
`switch`, which is the `if/else` OCP violation you were avoiding.

**Two strategies on orthogonal axes.** Allocation cares about spot size and
availability; pricing cares about customer and duration. A change to one never touches
the other.

**`PricingPolicy` selects, `PricingStrategy` computes.** Two responsibilities, two
objects. Merging them is the common mistake.

**Subscription belongs on the customer, not the vehicle.** Same car, different driver,
different terms. Extending `Vehicle` into `SubscribedCar` breaks the moment a
subscription lapses — you can't change an object's class. Pass it as context:
`policy.strategyFor(vehicle, customer)`.

**Configuration vs operation split by interface, not by an `Admin` class.** Roles are
an authorization concern; an `Admin` class models your org chart. Two interfaces on
one object is compiler-enforced.

### The lock-granularity upgrade (describe, don't necessarily build)

Current design serializes every park and unpark across the whole lot, including cars
on different floors that can't conflict.

**Lock per floor** requires restructuring — the strategy currently searches *across*
floors, so by the time you know which floor to lock, the search is done and the spot
can be taken. The fix:

```java
for (ParkingFloor floor : strategy.floorOrder(floors, vehicle)) {
    Optional<Ticket> t = floor.tryPark(vehicle);   // lock lives INSIDE the floor
    if (t.isPresent()) return t;
}
```

The strategy's role changes from "pick the spot" to "pick the floor order" — arguably
cleaner: policy decides where to look, the floor owns its own state.

*Never hold two floor locks at once* — if you add "move vehicle between floors",
acquire in ascending floor order or you have a deadlock.

**In an interview, saying this scores nearly as well as building it, and costs zero
minutes:**
> "This serializes the whole lot. I'd shard the lock per floor, or make the spot claim
> a CAS so threads only contend when they pick the same spot."

---

## 12. Cross-Language Transfer

### What's universal

Race conditions, check-then-act, critical sections, mutual exclusion, deadlock and
lock ordering, lock granularity, producer-consumer, "make check-and-claim one atomic
step." These aren't Java facts — they're facts about concurrent systems.

### LLD in Python: ~90% identical

| Java | Python |
|---|---|
| `interface X` | `class X(ABC)` + `@abstractmethod` |
| `enum VehicleType` | `class VehicleType(Enum)` |
| `record Point(int x, int y)` | `@dataclass(frozen=True)` |
| Builder | keyword args + `@dataclass` — usually unnecessary |
| Holder singleton | module-level object |

Patterns that essentially vanish in Python: Proxy (`__getattr__`), Prototype
(`copy.deepcopy`), Iterator (generators), Strategy (a plain callable).

**Duck typing makes ABCs optional — which is *worse* for interviews**, because your
design intent disappears. Use ABCs when you want the structure visible.

### Concurrency in Python: fundamentally different

**The GIL.** Only one thread executes bytecode at a time. Threads still **interleave**
at bytecode boundaries, so you still get every race condition you know. What you don't
get is CPU parallelism.

**No `volatile` needed, and here's why:**
1. The GIL is an OS mutex — acquire/release are full memory barriers, so *every*
   variable behaves as if volatile, for free.
2. CPython never caches attribute values in registers across bytecodes — attribute
   lookups go through a heap dict every time. So the classic hoisted-loop bug can't
   happen:

```java
while (!stopped) { }        // Java: JIT hoists the read → infinite loop
```
```python
while not self.stopped:     # Python: re-reads every iteration → terminates
    pass
```

**But atomicity is exactly as broken.** `self.count += 1` is still a race.

**Three execution models where Java has one:**

| Workload | Java | Python |
|---|---|---|
| I/O-bound | threads | `threading` (GIL released around blocking calls) or `asyncio` |
| CPU-bound | threads | `multiprocessing` (separate GILs) |
| High-concurrency I/O | virtual threads | `asyncio` |

Threads for CPU-bound Python work give **zero** speedup — often a slowdown from GIL
contention. C extensions (NumPy) can release the GIL themselves, which is why NumPy
achieves real parallelism.

**Missing tools:** no CAS, no atomics, no `ConcurrentHashMap` (dict ops are atomic
*under the GIL* — an implementation detail, not a guarantee, and it doesn't help with
check-then-act anyway; `dict.setdefault` is your `putIfAbsent`).

**`threading.Lock` is NOT reentrant** — acquiring twice in one thread deadlocks. Use
`RLock`. Java's `synchronized` and `ReentrantLock` are both reentrant.

**`with lock:`** is `try/finally` desugared via `__enter__`/`__exit__` — equivalent to
Java's `synchronized` block, and safer than `ReentrantLock` for the same reason.

**Concurrency vs parallelism:** Python has real **concurrency** (interleaved tasks),
and **parallelism** only via blocking I/O, GIL-releasing C extensions, or
`multiprocessing`. Critically: **interleaving alone produces every race you know** —
which is why the seat-booking bug is identical in both languages despite the GIL.

**Forward-looking:** free-threaded Python (PEP 703, landing since 3.13) removes the
GIL. At that point Python inherits real visibility problems and the JMM concepts
become directly applicable.

### Rust: where the Java work pays off most

`Mutex<T>`, `RwLock<T>`, `AtomicUsize`, `mpsc` channels, `Arc` are direct analogues.
And **Rust's memory ordering (`SeqCst`, `Acquire`, `Release`) IS the happens-before
model** — just made explicit at each operation instead of inferred from `volatile`.
Java's JMM heavily influenced the C++11 model Rust adopted.

The difference: Rust makes **data races a compile error** via ownership — you can't
share a mutable reference across threads without a lock. It does **not** prevent
deadlocks or logical races (double-booking) — those are still on you, which is exactly
where the Parking Lot reasoning applies.

---

## 13. Interview Quick Reference

### One-liners

| Question | Answer |
|---|---|
| Factory Method vs Abstract Factory | "Factory Method creates one product, varied by subclassing. Abstract Factory creates a family of related products, varied by injection. Use the second when mismatching the products would be a bug." |
| Why is the holder singleton thread-safe? | "The JVM guarantees `<clinit>` runs exactly once under its own init lock, and defers it until first active use — so it's lazy and thread-safe with no synchronization code." |
| What does `volatile` do? | "Guarantees visibility and prevents reordering. Not atomicity — `count++` is still broken." |
| `volatile` vs `synchronized` | "`volatile` fixes visibility and reordering; `synchronized` fixes those plus atomicity, because it's mutual exclusion." |
| What is happens-before? | "An ordering relation: if A happens-before B, everything A did is visible to B. Without an edge, the compiler and CPU can reorder and B may see stale state." |
| Why does `equals` require `hashCode`? | "The contract: equal objects must have equal hash codes. Break it and a `HashMap` won't find an object you just inserted, because it looks in the wrong bucket." |
| Interface vs abstract class | "Abstract class when you have shared state or a partial implementation with a template method. Interface when you only need a contract — and you can implement many." |
| Composition vs inheritance | "Inheritance couples you to the parent's implementation and can only be chosen once. Composition can change at runtime and keeps you honest about what you actually depend on." |
| Why not Singleton? | "There can be more than one instance in the real domain, it breaks across multiple JVMs, and global state is hard to test. I'd inject the object instead." |
| Is your code thread-safe? | "The spot claim is atomic because find-and-claim are in one critical section. It serializes the whole lot though — I'd shard per floor or use a CAS on the spot." |

### The 90-minute budget

| Phase | Time | Deliverable |
|---|---|---|
| Clarify + assumptions | 10 min | Stated out loud, written down |
| Entities + class diagram | 15 min | Nouns → classes, verbs → methods |
| Code | 50 min | Happy path first |
| Working `main()` demo | 15 min | **This is what gets run first** |

**Earns marks:** a runnable demo, in-memory storage, interfaces at extension points,
thread safety where it matters, meaningful names, no god class.

**Loses marks:** over-engineering with six patterns, no working output, building
persistence nobody asked for, running out of time on a `DisplayBoard`.

### Design smells → fix

| Smell | Fix |
|---|---|
| Growing `if/else` on a type field | Strategy |
| `instanceof` branch for container vs leaf | Composite |
| `new` on a concrete dependency inside a class | Constructor injection (DIP) |
| Override throws `UnsupportedOperationException` | Wrong inheritance (LSP) — use composition |
| Empty method bodies in an implementation | Interface too fat (ISP) |
| Class name contains "and" | Split it (SRP) |
| Read shared state, then write based on it | Check-then-act — make it atomic |
| `synchronized` on every method | Over-locking — lock at the invariant |
| Setter on every field | No invariants protected — use domain methods |

### Session checklist for any concurrent design

1. What is the invariant? ("no seat is held by two users")
2. Who owns the truth? (heap / DB / distributed)
3. Is there a check-then-act gap?
4. Is there a *canonical* object to lock on?
5. Is the lock at the level of the invariant, or scattered?
6. Does the test have an assertion that can actually fail?

---

## Appendix: What Was Deliberately Skipped

Not needed for machine-coding rounds; revisit later if useful.

**Patterns:** Flyweight, Bridge, Memento, Visitor, Interpreter, Prototype, Adapter,
Decorator, Proxy (know the names, that's enough).

**Concurrency:** Fork-Join internals, `Phaser`, `Exchanger`, `StampedLock` optimistic
reads, the formal JMM axioms.

**Memory:** GC tuning flags, escape analysis internals, JVMTI, heap dump analysis with
MAT, JFR.

**Java:** modules, reflection, JDBC, networking, wildcards and bounded generics,
Lombok (records cover the same ground natively with no build setup).

**Books for after:** *Effective Java* (Bloch), *Java Concurrency in Practice* (Goetz),
*Head First Design Patterns* for pattern intuition. Jenkov's tutorial for targeted
concurrency lookup.