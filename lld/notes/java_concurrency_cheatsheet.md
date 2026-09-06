# Java Concurrency — Cheat Sheet

> Revision reference. Organised by *what you're trying to do*, not by API.
> Current as of Java 21/25. Notes where older material is stale.

---

## 0. The Decision Table

Start here. Find your situation, use that tool.

| Situation | Tool |
|---|---|
| Publish a flag or reference; no read-modify-write | `volatile` |
| Counter, single value, read-modify-write | `AtomicLong` / `AtomicInteger` + CAS loop |
| Several fields that must change together | `synchronized`, or `AtomicReference<ImmutableRecord>` |
| Invariant spans several **distinct objects** | **lock — nothing else works** |
| Need `tryLock`, timeout, or interruptible acquire | `ReentrantLock` |
| Threads must wait for a condition to become true | `ReentrantLock` + `Condition`, or `wait`/`notifyAll` |
| Many readers, rare writers | `ReadWriteLock` / `StampedLock` |
| Hand work between threads | `BlockingQueue` |
| Concurrent map with atomic compound ops | `ConcurrentHashMap` + `putIfAbsent` / `computeIfAbsent` / `merge` |
| Many readers, almost no writers, need iteration | `CopyOnWriteArrayList` |
| Wait for N events to finish (one-shot) | `CountDownLatch` |
| N threads wait for each other, repeatedly | `CyclicBarrier` |
| Bound concurrent access to N resources | `Semaphore` |
| Run N tasks on a pool | `ExecutorService` |
| Expire something after a timeout | `ScheduledExecutorService` |
| Millions of concurrent blocking tasks | Virtual threads (Java 21+) |

**The governing rule:**

> **Match the mechanism to the SPAN of the invariant.**
> One memory location → CAS. Several fields of one object → immutable snapshot + CAS, or a lock.
> Several distinct objects → lock. Java has no multi-word CAS.

---

## 1. The Three Problems

Everything below fixes one of three things. Conflating them is where most confusion lives.

| Problem | Symptom | Fixed by |
|---|---|---|
| **Visibility** | thread A writes, B never sees it | `volatile`, locks, atomics |
| **Reordering** | compiler/JIT/CPU reorder freely | `volatile`, locks, atomics |
| **Atomicity** | `count++` is read-modify-write | locks, atomics — **NOT `volatile`** |

`volatile` fixes 1 and 2. Locks and atomics fix all three.

---

## 2. Happens-Before (the JMM in one page)

> **"A happens-before B" means everything A wrote is guaranteed visible to B.**
> It says nothing about wall-clock order.

Without an edge, the compiler and CPU may reorder, and B may see stale or partially-written state.

### The edges

| Edge | Rule |
|---|---|
| Program order | within one thread, earlier → later |
| Monitor | unlock → any **subsequent** lock of the **same** monitor |
| Volatile | write to a volatile field → every subsequent read of it |
| Thread start | `t.start()` → everything inside `t` |
| Thread join | everything in `t` → `t.join()` returning |
| Final fields | the constructor freeze (below) |

**Transitive:** A → B and B → C gives A → C.

### The piggyback effect

A volatile write publishes **everything the thread wrote before it**, not just that field.

```java
private volatile boolean initialized = false;
private Config config;                        // NOT volatile

void init() {
    config = loadConfig();                    // ordinary write
    initialized = true;                       // volatile write — publishes config too
}
void use() {
    if (initialized) config.get(...);         // guaranteed to see the fully-built config
}
```

This is also why double-checked locking needs `volatile`: without it, another thread can see a non-null reference to an object whose constructor hasn't finished.

### Final-field semantics (JLS 17.5)

> All fields `final` + `this` never escapes the constructor ⇒ **any thread that gets the reference sees fully-initialized fields**, with no synchronization.

A freeze at the end of the constructor prevents reordering the field writes after the reference publication. Non-final fields get **no** such guarantee.

```java
private Pizza(Builder b) {
    this.size = b.size;
    REGISTRY.add(this);        // BUG: `this` escapes before construction completes
}
```

This is why immutable objects and records are safe to share, and why the holder-idiom singleton needs no `volatile`.

---

## 3. `volatile`

```java
private volatile boolean running = true;      // THE canonical use: a stop flag

public void stop() { running = false; }       // written on one thread
public void run() { while (running) { ... } } // read on another
```

**Without `volatile`** the JIT hoists the read out of the loop — `while (running)` becomes `if (running) while(true)` — and the thread never sees the change.

**What it does NOT do — atomicity:**

```java
volatileCounter++;         // STILL BROKEN — read, increment, write
```

**Critical subtlety:** `volatile` on a *reference* protects the reference, not the object.

```java
private volatile Truck shared;    // guarantees you see the latest POINTER
                                  // guarantees NOTHING about shared.location
```

**Cost:** memory barriers, architecture-dependent. On **x86** a volatile read is essentially free (hardware already gives the ordering; only the compiler is restrained); a write compiles to a locked instruction used as a fence. On **ARM** both directions need explicit `dmb` barriers. "volatile is cheap" is an x86 statement.

---

## 4. Atomics + the CAS loop

```java
private final AtomicLong counter = new AtomicLong();

// The idiom: read → decide → conditionally write → retry on loss
while (true) {
    long current = counter.get();
    if (current >= limit) return false;                       // reject
    if (counter.compareAndSet(current, current + 1)) return true;   // won
    // lost — someone else changed it; loop, re-read, re-decide
}
```

**What `compareAndSet` compiles to** — a JIT intrinsic, one hardware instruction:

- **x86:** `LOCK CMPXCHG`
- **ARM:** `LDXR` / `STXR` (load-exclusive / store-exclusive pair)

Path: `AtomicLong.compareAndSet` → `VarHandle` (Java 9+; `Unsafe` before) → intrinsic → machine instruction.

### The idiomatic short form

```java
long prev = counter.getAndUpdate(cur -> cur < limit ? cur + 1 : cur);
return prev < limit;
```

`getAndUpdate` runs the retry loop internally. Your function **must be side-effect free** — it may be applied several times.

### Compound state: CAS an immutable snapshot

Java has no double-word CAS. When two fields must change together:

```java
record SeatState(Status status, String heldBy, long expiryMillis) {}   // IMMUTABLE

private final AtomicReference<SeatState> state = new AtomicReference<>(SeatState.available());

boolean tryHold(String userId, long now, long ttl) {
    while (true) {
        SeatState cur = state.get();
        if (!isFree(cur, now)) return false;
        if (state.compareAndSet(cur, SeatState.heldBy(userId, now + ttl))) return true;
    }
}
```

**The trap:** `AtomicReference<MutableThing>` gives you nothing. CAS compares **references** — mutate in place and the reference never changes, so the CAS always "succeeds" while another thread mutates underneath. **The held object must be immutable.**

### Lock-free ≠ wait-free

| Property | Guarantee |
|---|---|
| **Lock-free** | *some* thread always progresses; an individual thread may retry indefinitely |
| **Wait-free** | *every* thread completes in bounded steps |
| **Blocking** | one stalled thread can halt everyone |

A retry means someone else succeeded, so the system never stalls. Starvation of an individual thread is theoretically possible; in practice most CAS loops are self-limiting (the retry condition eventually fails).

### `LongAdder` — the scaling answer

Under heavy contention on one counter, every core invalidates the same cache line. `LongAdder` shards into per-thread cells and sums on read.

```java
LongAdder hits = new LongAdder();
hits.increment();                 // striped, low contention
long total = hits.sum();          // approximate if concurrent writes are in flight
```

Use it for metrics/counters. Not when you need the exact value at each increment.

---

## 5. `synchronized`

```java
private final Object lock = new Object();     // PRIVATE — not `this`

synchronized (lock) { ... }                   // block form
public synchronized void m() { ... }          // locks `this`
public static synchronized void s() { ... }   // locks ClassName.class
```

### Why a private lock object

`this` is public, so its monitor is a public resource:

```java
synchronized (someonesObject) { Thread.sleep(60_000); }   // freezes their class
```

They never touched your fields. You can't prevent or detect it. A private field has no reference outside your class.

*(It buys encapsulation, not performance — both are one monitor, identical cost.)*

### Internals: mark word and inflation

Every object header has a **mark word** encoding lock state. HotSpot escalates:

- **Thin (lightweight) lock** — uncontended. CAS a pointer to a stack-allocated lock record into the mark word. No OS involvement, tens of nanoseconds. **This is the common case — "synchronized is slow" is outdated folklore.**
- **Inflated (heavyweight)** — under contention, inflates to a full `ObjectMonitor` with wait/entry queues; blocked threads **park** via the OS (futex on Linux). Context switches. This is where the real cost lives.

**Biased locking was removed in JDK 18** (deprecated JDK 15). Many blog posts still describe it as current — they're stale.

**Bytecode:** a `synchronized` *block* → `monitorenter`/`monitorexit`, plus a second `monitorexit` in a compiler-generated exception handler (so it releases on throw). A `synchronized` *method* has no such instructions — it carries the `ACC_SYNCHRONIZED` flag and the JVM handles the monitor.

**Reentrancy:** tracked by a recursion count in the lock record. Without it, a synchronized method calling another on the same object would self-deadlock.

### JIT optimizations worth knowing

- **Lock elision** — escape analysis proves the object never escapes the thread ⇒ locks removed entirely
- **Lock coarsening** — adjacent blocks on the same monitor merged

This is why `StringBuffer` in a local variable costs roughly what `StringBuilder` costs.

---

## 6. `ReentrantLock` and AQS

Not a JVM primitive — **library code in Java**, built on `AbstractQueuedSynchronizer`.

```java
private final ReentrantLock lock = new ReentrantLock();

lock.lock();
try { ... } finally { lock.unlock(); }        // try/finally is MANDATORY
```

### AQS internals

A `volatile int state` plus a FIFO queue of waiting threads (CLH variant).

- **Acquire:** CAS `state` 0→1. Won? You own it, no OS involvement — same fast path as a thin lock. Lost? Enqueue as a node and park via `LockSupport.park()` (futex).
- **Release:** set `state` to 0, unpark the successor.

**The unification:** that `state` field is `volatile`. The happens-before edge from a `ReentrantLock` comes from *exactly the same mechanism* as `volatile`. `synchronized` and `ReentrantLock` differ in **where the queueing logic lives** (C++ inside the JVM vs Java in the library), not in the underlying primitives. Everything bottoms out in **CAS + memory barriers**.

Same for `Semaphore`, `CountDownLatch`, `ReentrantReadWriteLock` — all AQS, differing only in what `state` means:

| | `state` means |
|---|---|
| `Semaphore` | permits remaining |
| `CountDownLatch` | events remaining |
| `ReentrantLock` | hold count (0 = free) |

### Fairness

`new ReentrantLock(true)` — FIFO, no starvation, meaningfully slower.
Default (unfair) lets an arriving thread **barge** and CAS ahead of the queue — higher throughput, possible starvation.

**Note `synchronized` is also unfair.** "Locks prevent starvation" is false.

### Which to default to

**`synchronized`.** It can't leak — the compiler-generated handler releases on exception — while a forgotten `unlock()` hangs your application permanently. Reach for `ReentrantLock` when you need a capability `synchronized` structurally cannot offer: `tryLock`, timeout, interruptibility, fairness, or multiple `Condition`s.

---

## 7. `Condition` — waiting for a state change

The tool for "sleep until something becomes true."

```java
private final ReentrantLock lock = new ReentrantLock();
private final Condition hasNewData = lock.newCondition();

// PRODUCER
public void append(Message m) {
    lock.lock();
    try {
        log.add(m);
        hasNewData.signalAll();          // ring the doorbell
    } finally { lock.unlock(); }
}

// CONSUMER
public boolean awaitData(long offset, long timeoutMillis) throws InterruptedException {
    lock.lock();
    try {
        long deadline = System.nanoTime() + MILLISECONDS.toNanos(timeoutMillis);
        while (offset >= log.size()) {                   // WHILE, never IF
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;            // timed out
            hasNewData.awaitNanos(remaining);            // releases lock + parks
        }
        return true;
    } finally { lock.unlock(); }
}
```

### The mechanics

`await` does two things **atomically**: releases the lock, and parks the thread. On the way out it **reacquires** the lock before returning.

- **Why release?** Sleeping while holding the lock deadlocks — the producer could never append.
- **Why atomic?** If it released then parked as two steps, a producer could slip between, signal an empty wait set, and leave. The consumer parks and misses it. That's the **lost wakeup** problem.

### The three rules

**1. `while`, never `if`.** `signalAll` wakes every waiter, and spurious wakeups are legal. A woken thread must re-verify — another consumer may have taken what it was woken for.

**2. `signalAll` unless you can prove all waiters are interchangeable.** `signal()` wakes one arbitrary waiter; if waiters want *different* things, it can wake the wrong one, which re-waits, and the right one is never signalled. Deadlock with no obvious cause.

**3. Use a timeout.** Unbounded `await()` means a thread can never re-check a `running` flag, so shutdown hangs. `awaitNanos(remaining)` recomputed from a deadline also prevents a spurious wakeup from restarting the full timeout.

`Object.wait()`/`notifyAll()` are the same mechanism on an object's built-in monitor — same `while` rule, same lock-release semantics.

---

## 8. Executors and Threads

```java
ExecutorService pool = Executors.newFixedThreadPool(4);
ExecutorService pool = Executors.newCachedThreadPool();          // unbounded, 60s keep-alive
ExecutorService pool = Executors.newSingleThreadExecutor();      // ORDERING guaranteed
ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor();
ExecutorService v = Executors.newVirtualThreadPerTaskExecutor(); // Java 21+
```

### Custom thread factory — daemon threads

```java
Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "worker");
    t.setDaemon(true);                    // JVM can exit without waiting for these
    return t;
});
```

**Without daemon:** if you forget `shutdown()`, `main()` returns and the JVM **hangs forever**. Daemon is a safety net, not a substitute — daemon threads are killed abruptly, losing in-flight work.

### Shutdown — order matters

```java
workers.forEach(Worker::stop);                       // 1. flip volatile flags FIRST
pool.shutdown();                                     // 2. stop accepting; running tasks continue
if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {   // 3. wait for the drain
    pool.shutdownNow();                              // 4. interrupt what's stuck
}
```

| Method | Behaviour |
|---|---|
| `shutdown()` | stop accepting new tasks; **running tasks keep running**; returns immediately |
| `awaitTermination(t, u)` | block up to `t`; returns `true` if everything finished |
| `shutdownNow()` | interrupt running threads, discard queued tasks, return the discarded list |

**Flip the flags before `shutdown()`**, or infinite loops keep polling and `awaitTermination` always times out.

### `ThreadPoolExecutor` rejection policies

When the queue is full:

| Policy | Behaviour |
|---|---|
| `AbortPolicy` (default) | throw `RejectedExecutionException` |
| `CallerRunsPolicy` | the submitting thread runs it — natural backpressure |
| `DiscardPolicy` | drop the new task silently |
| `DiscardOldestPolicy` | drop the oldest queued task |

This is the standard vocabulary for "what happens when a bounded queue fills" — drop-newest, drop-oldest, or block.

### Virtual threads (Java 21+)

```java
Thread.startVirtualThread(() -> { ... });
try (var exec = Executors.newVirtualThreadPerTaskExecutor()) { exec.submit(task); }
```

**Mechanism:** a virtual thread **mounts** onto a carrier (platform) thread from a ForkJoinPool. When it blocks on I/O it **unmounts** — its stack is copied to the heap, freeing the carrier. 10,000 virtual threads can share 4 carriers.

**The common misconception:** virtual threads are **not** what gives Java CPU parallelism. Platform threads always did. Virtual threads are for **scaling concurrency, not creating parallelism**. For CPU-bound work a virtual thread never unmounts — no benefit, no penalty.

**Use for:** many blocking I/O tasks. **Don't pool them** — they're cheap enough that pooling is an anti-pattern.

**Stale warning:** in Java 21, blocking inside `synchronized` **pinned** a virtual thread to its carrier. **JEP 491 fixed this in JDK 24** — `synchronized` no longer pins. Many articles still warn about it.

---

## 9. Coordination Primitives

### `CountDownLatch` — one-shot

```java
CountDownLatch startGate = new CountDownLatch(1);      // START GATE: 1 releases many
CountDownLatch done      = new CountDownLatch(N);      // COMPLETION: many release 1

// worker:  startGate.await(); ... finally { done.countDown(); }
startGate.countDown();     // fire all workers simultaneously
done.await();              // wait for all to finish
```

**One-shot** — once at zero it stays there. `CyclicBarrier` is the reusable version (N threads wait for *each other*).

**The start gate is why concurrency tests can detect anything.** Without it, thread 1 finishes before thread N is created and they never overlap.

### `Semaphore` — bounded resources

```java
Semaphore permits = new Semaphore(10);
if (!permits.tryAcquire(2, TimeUnit.SECONDS)) throw new IllegalStateException("pool exhausted");
try { ... } finally { permits.release(); }
```

**Not reentrant** — acquiring twice from one thread with one permit deadlocks.
**Permits aren't owned** — any thread can `release()`, including one that never acquired. That's a bug source *and* the feature: it's what makes semaphores work for **signalling** rather than mutual exclusion. A lock can only be released by its holder.

### `BlockingQueue` — producer-consumer

```java
BlockingQueue<Task> q = new LinkedBlockingQueue<>(1000);   // BOUNDED — always bound it

q.put(task);                    // blocks if full  — backpressure
q.offer(task);                  // returns false if full — drop policy
Task t = q.take();              // blocks until available
Task t = q.poll(1, SECONDS);    // bounded wait
```

Unbounded queues turn a slow consumer into an `OutOfMemoryError`.

---

## 10. Concurrent Collections

| Need | Use |
|---|---|
| General concurrent map | `ConcurrentHashMap` |
| Sorted concurrent map / navigable ops | `ConcurrentSkipListMap` |
| Many reads, almost no writes, safe iteration | `CopyOnWriteArrayList` |
| Concurrent set | `ConcurrentHashMap.newKeySet()` |
| Producer-consumer handoff | `LinkedBlockingQueue` / `ArrayBlockingQueue` |
| Concurrent priority queue | `PriorityBlockingQueue` |

**Avoid:** `Hashtable`, `Vector`, `Collections.synchronizedMap` — every method synchronized, still useless for compound operations, and slower.

### The atomic compound operations — use these

```java
map.putIfAbsent(k, v);                          // atomic check-and-set
map.computeIfAbsent(k, key -> expensive(key));  // atomic create-if-missing
map.merge(k, 1, Integer::sum);                  // atomic counter
map.compute(k, (key, old) -> ...);
map.remove(k);                                  // atomic; returns the value — one winner
```

### The rule you must internalize

> **A thread-safe container protects its own operations, not your multi-step operation.**

```java
if (!map.containsKey(k)) {   // CHECK — atomic
    map.put(k, v);           // ACT — atomic
}                            // ...and BOTH threads still get through
```

Each call is atomic; **the gap between them is not.** Use `putIfAbsent`.

`CopyOnWriteArrayList` copies the whole array on every write — perfect for listener lists, terrible for anything write-heavy. Its iterator is a snapshot, so it never throws `ConcurrentModificationException`.

---

## 11. Check-Then-Act — the universal bug

```
read shared state          ← CHECK
──── gap ────              ← another thread acts here
write based on the read    ← ACT
```

### The same bug in five costumes

| Domain | Check | Act | Symptom |
|---|---|---|---|
| Parking spot | `findFreeSpot()` | `spot.park()` | 30 cars, one spot |
| Rate limiter | `counter.get()` | increment | more than N allowed |
| Seat booking | `containsKey(seat)` | `put(seat, user)` | two users, one seat |
| Show schedule | `floorKey`/`ceilingKey` | `put(slot, show)` | double-booked screen |
| Database row | `SELECT WHERE free` | `UPDATE SET taken` | lost update |

### The three fixes

**1. Lock across both steps**
```java
synchronized (lock) {
    spot = strategy.findSpot(floors, vehicle);
    spot.park(vehicle);                          // find AND claim, one critical section
}
```
Splitting these across two `synchronized` *methods* does nothing — the gap survives.

**2. Make the claim atomic**
```java
booked.putIfAbsent(seat, user) == null;          // one winner
occupied.compareAndSet(false, true);
```

**3. Make acquisition itself the claim**
```java
Connection c = pool.take();                      // BlockingQueue — checkout IS the claim
```

### Where the invariant lives determines the fix

| Authority | Mechanism |
|---|---|
| One JVM | `synchronized` / CAS / `BlockingQueue` |
| Database | conditional `UPDATE ... WHERE status='FREE'`, `@Version`, `SELECT ... FOR UPDATE` |
| Multiple services | distributed lock (Redis) or a DB row as arbiter |

```sql
-- putIfAbsent, written in SQL. Rows-affected decides the winner.
UPDATE seats SET user_id = ? WHERE id = ? AND user_id IS NULL;
```

> **Thread-safe objects and a safe resource are different claims.** Your objects can be
> perfectly thread-confined while the underlying resource gets double-booked.
> Ask **"who owns the truth?"** and synchronize there.

---

## 12. Deadlock, Livelock, Starvation

### The four Coffman conditions

Deadlock requires **all four**. Break any one and it's impossible.

| Condition | Break it by |
|---|---|
| Mutual exclusion | (rarely breakable) |
| **Hold and wait** | all-or-nothing acquisition; **CAS never waits** |
| No preemption | `tryLock(timeout)` — release and retry |
| **Circular wait** | **global lock ordering** — always acquire in the same order |

### Global ordering — the standard fix

```java
List<String> sorted = seatIds.stream().sorted().toList();   // everyone acquires ascending
for (String id : sorted) { ... }
```

X wants {A5,A6}, Y wants {A6,A5} — both sort to the same sequence, so no cycle.
Weakness: every code path must honour the convention, forever.

### With CAS, deadlock is structurally impossible

`compareAndSet` returns immediately. No waiting ⇒ hold-and-wait broken ⇒ no cycle can form.

**But you get livelock instead:**

| | Deadlock | Livelock |
|---|---|---|
| Threads are | blocked | running, retrying |
| CPU | zero | high |
| Detection | thread dump shows the cycle | looks like a busy system doing nothing |
| Resolves itself? | never | usually, with backoff |
| Caused by | blocking acquisition | fail-fast acquisition |

**Cure:** randomized backoff before retry (same idea as Ethernet's exponential backoff).

### Retry vs fail-fast

> **Retry when the contended resources are interchangeable. Fail fast when the user named specific ones.**

| Case | Behaviour |
|---|---|
| Any parking spot will do | retry another |
| CAS lost on a counter | retry — fresh data changes the outcome |
| "Give me any 3 seats" | retry — re-select from fresh availability |
| "Give me A5, A6, A7" | **fail fast** — retrying can only fail identically |

In a UI, **the human is the retry loop**, and they retry with *different input* — which a machine retry can't do.

---

## 13. Testing Concurrent Code

> **A passing concurrency test proves nothing.** Races are intermittent; a test that merely
> runs without crashing has told you nothing.

### The four required elements

```java
final int THREADS = 100, LIMIT = 10;          // 1. TINY resource, MANY threads

CountDownLatch startGate = new CountDownLatch(1);      // 2. START GATE
CountDownLatch done      = new CountDownLatch(THREADS);
ExecutorService pool     = Executors.newFixedThreadPool(THREADS);
AtomicInteger allowed    = new AtomicInteger();
AtomicInteger errors     = new AtomicInteger();

for (int i = 0; i < THREADS; i++) {
    pool.submit(() -> {
        try {
            startGate.await();
            if (subject.tryAcquire()) allowed.incrementAndGet();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (RuntimeException e)       { errors.incrementAndGet(); }   // bugs, not results
        finally { done.countDown(); }
    });
}

startGate.countDown();
done.await();                                  // 3. COMPLETION GATE
pool.shutdown();
pool.awaitTermination(10, TimeUnit.SECONDS);

// 4. AN ASSERTION THAT CAN FAIL
System.out.println(allowed.get() == LIMIT ? "PASS" : "FAIL: got " + allowed.get());
```

Without the **start gate**, thread 1 finishes before thread 100 exists — they never overlap and the race can't fire. Without the **assertion**, it's a demo.

### Widening the race window

A race between a check and a write is ~3 bytecodes — nanoseconds. You may run thousands of times without seeing it. Force it:

```java
if (isOccupied) return false;
try { Thread.sleep(100); } catch (InterruptedException e) {}   // ← WIDEN
this.isOccupied = true;
```

**Real result from doing this:** a Parking Lot that passed cleanly went to *30 tickets issued for 5 spots, all on the same spot* — 100% reproducible.

Then **restore the lock with the sleep still in place** and confirm it passes. That proves the lock *closes* the window rather than merely narrowing it.

### Conservation checks

Success count alone is ambiguous — low success could be contention *or* a leak.

```
available + booked == poolSize        // every resource accounted for
```

### Reading latency distributions

Bimodal p50/p99 tells you *where* requests are dying, with no extra instrumentation:

```
p50 = 13ms      p95 = 36ms      p99 = 531ms
```
→ the mass at 13ms failed early and returned; only the top 1% reached the 500ms payment call.

### Other rules

- **Comment out domain `println`s** — I/O serializes threads and actively **hides** races
- **Run ~20 times.** One green run means little
- **Count exceptions separately from failures** — a `RuntimeException` escaping is a bug, not a business outcome
- **Check your test parameters before your code.** A limiter that "allows everything" usually has an unreachable failure condition (1ms window, refill faster than drain, every request on a different key)

---

## 14. Idioms Worth Memorising

### Thread-safe lazy singleton (holder idiom)

```java
public final class Registry {
    private Registry() { }
    private static class Holder { static final Registry INSTANCE = new Registry(); }
    public static Registry getInstance() { return Holder.INSTANCE; }
}
```

**Lazy** because the JVM defers class *initialization* until first active use. **Thread-safe** because `<clinit>` runs exactly once under the JVM's own init lock. No `volatile`, no `synchronized`, no double-check.

*(Caveat: a throwing constructor gives `ExceptionInInitializerError` and the class is permanently erroneous. Enum singleton is the only form immune to reflection and serialization attacks.)*

### Double-checked locking (when you need arguments)

```java
private volatile Instance instance;        // volatile is MANDATORY

public Instance get() {
    Instance result = instance;
    if (result == null) {
        synchronized (this) {
            result = instance;
            if (result == null) instance = result = new Instance();
        }
    }
    return result;
}
```

Drop the `volatile` and another thread can see a non-null reference to a half-constructed object.

### Immutable snapshot published from inside a lock

```java
public synchronized ElevatorState getState() {
    return new ElevatorState(id, currentFloor, direction, pending.size());
}
```

**Individual getters don't compose.** Reading `floor` then `direction` in two calls can give you floor 5 with the direction from after the move — a combination that never existed. One method, one lock acquisition, all fields.

### Per-key locking

```java
Bucket b = buckets.computeIfAbsent(key, k -> new Bucket());
synchronized (b) { ... }      // different keys never contend
```

Fine-grained by construction. Beats one global lock.

### Never do slow work inside a critical section

```java
lock.lock();
try { event = log.get(index); offset++; }
finally { lock.unlock(); }

handler.onMessage(event);      // ← OUTSIDE. Arbitrary user code, possibly slow.
```

One slow consumer must not block every producer. Same principle: payment goes **outside** the seat lock; the elevator's handler runs **outside** the topic lock.

### Lock at the level of the invariant

```java
// ParkingSpot.park() — NO synchronized here
```

Every caller already holds the lot's lock. Adding it is a second uncontended lock — and worse, it **advertises** that the method is safe to call standalone, which it isn't.

Sprinkling `synchronized` on every method is the classic over-locking mistake: code that looks thread-safe, isn't, and is slower. `Vector` and `Hashtable` are the canonical examples.

---

## 15. Python Comparison (for translation)

**Universal:** race conditions, check-then-act, critical sections, deadlock, lock ordering, producer-consumer.

| Java | Python |
|---|---|
| `synchronized` | `with lock:` (`try/finally` desugared) |
| `ReentrantLock` | `threading.RLock` (**`Lock` is NOT reentrant**) |
| `Semaphore` | `threading.Semaphore` |
| `Condition` | `threading.Condition` |
| `BlockingQueue` | `queue.Queue` |
| `ExecutorService` | `concurrent.futures.ThreadPoolExecutor` |
| `CountDownLatch` | `threading.Event` / `Barrier` |
| `volatile` | **not needed** — the GIL is a barrier at every bytecode |
| `ConcurrentHashMap` | `dict` (atomic *under the GIL*, not a guarantee); `setdefault` ≈ `putIfAbsent` |
| CAS / atomics | **no equivalent** — use a lock |

**The GIL:** only one thread executes bytecode at a time. Threads still **interleave** at bytecode boundaries, so every race you know still happens — `self.count += 1` is still broken. What you lose is CPU parallelism.

**Three execution models where Java has one:**

| Workload | Python |
|---|---|
| I/O-bound | `threading` (GIL released around blocking calls) or `asyncio` |
| CPU-bound | `multiprocessing` (separate GILs) |
| High-concurrency I/O | `asyncio` |

**Forward-looking:** free-threaded Python (PEP 703, landing since 3.13) removes the GIL. At that point Python inherits real visibility problems and the JMM concepts become directly applicable.

**Rust:** `Mutex<T>`, `RwLock<T>`, `AtomicUsize`, `mpsc` map directly. Rust's `Ordering::{SeqCst, Acquire, Release}` **is** the happens-before model, made explicit per-operation. Ownership makes data races a *compile error*; it does **not** prevent deadlocks or logical races.

---

## 16. Interview One-Liners

| Question | Answer |
|---|---|
| What does `volatile` do? | "Guarantees visibility and prevents reordering. Not atomicity — `count++` is still broken." |
| `volatile` vs `synchronized`? | "`volatile` fixes visibility and reordering; `synchronized` adds atomicity, because it's mutual exclusion." |
| What is happens-before? | "An ordering relation: if A happens-before B, everything A did is visible to B. Without an edge, the compiler and CPU can reorder and B may see stale state." |
| Why is the holder singleton thread-safe? | "The JVM guarantees `<clinit>` runs exactly once under its own init lock, and defers it until first active use — so it's lazy and thread-safe with no synchronization code." |
| Why CAS here and a lock there? | "CAS is atomic over one memory location. When the invariant spans several objects, only a lock covers that span." |
| Is a CAS loop safe from starvation? | "Lock-free, not wait-free — a thread can theoretically retry indefinitely, but a retry only happens when another thread succeeded, so the system always progresses." |
| How do you avoid deadlock? | "Break one Coffman condition. With blocking locks: a global acquisition order. With CAS: nothing waits, so no cycle can form — but you can get livelock, so add randomized backoff." |
| Is your code thread-safe? | "The claim is atomic because check and act are in one critical section. It serializes the whole structure though — I'd shard per key, or use a CAS so threads only contend on the same item." |
| Are virtual threads faster? | "They scale concurrency, not parallelism. Platform threads always gave you CPU parallelism; virtual threads let you have a million blocked tasks without a million OS threads." |

---

## 17. Stale Material to Watch For

Things widely written that are no longer true:

| Claim | Status |
|---|---|
| "Biased locking makes uncontended `synchronized` free" | **Removed in JDK 18** |
| "`synchronized` is slow, always use `ReentrantLock`" | False — thin locks are tens of nanoseconds |
| "Virtual threads get pinned by `synchronized`" | **Fixed by JEP 491 in JDK 24** |
| "PermGen holds class metadata" | Replaced by **Metaspace** in Java 8 |
| "`final` makes methods faster" | No — the JIT already inlines via class hierarchy analysis |
| "`Vector`/`Hashtable` are thread-safe, so they're fine" | Per-method only; useless for compound operations |
| "Allocating lots of objects is expensive" | GC cost scales with **survivors**, not garbage |