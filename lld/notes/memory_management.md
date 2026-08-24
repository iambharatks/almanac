# Memory, Garbage Collection & Concurrency — Revision Notes

*Personal notes. Covers: memory architecture, allocation across Java/Python/C++/Go, garbage collection internals, virtual threads, and the practical rules that follow. All benchmark figures are measured, not quoted — machine spec in Appendix A.*

---

# Table of contents

**Part I — Memory architecture**
1. The hardware floor
2. The complete storage taxonomy
3. Why the stack is essentially free
4. Why the heap costs more — the four separate costs
5. Measured evidence
6. How each language allocates

**Part II — Garbage collection**
7. Python: reference counting + cycle detection
8. Java: tracing + generational collection
9. Side-by-side, and what it changes in your code

**Part III — Concurrency**
10. Platform threads vs virtual threads
11. Concurrency vs parallelism — the distinction that decides everything
12. Parallel streams and Fork/Join
13. Pinning, thread locals, rate limiting

**Part IV — Practical application**
14. The six rules
15. Per-language cheat sheet
16. Where this shows up in LLD
17. Java ↔ Python mapping
18. Interview-ready statements

**Appendices** — machine spec, verification commands, glossary, corrected folklore

---

# PART I — MEMORY ARCHITECTURE

## 1. The hardware floor

Everything in these notes is downstream of one fact: **the CPU is roughly 100× faster than main memory.** Every optimisation, every allocator design, every GC strategy exists to hide that gap.

### 1.1 Latency, in order of magnitude

| Level | Approx. latency | Relative |
|---|---|---|
| Register | ~0.3 ns | 1× |
| L1 cache | ~1 ns | 3× |
| L2 cache | ~3 ns | 10× |
| L3 cache | ~10 ns | 30× |
| Main memory (DRAM) | ~100 ns | 300× |
| Syscall (user → kernel) | ~300 ns | 1000× |

Read that as: **an L1 hit is essentially free; a DRAM access costs about 300 instructions' worth of time.** The CPU stalls for that entire period unless it has other independent work to do.

### 1.2 The cache line — the single most important concept

The CPU **never fetches one variable.** It fetches a fixed-size block called a **cache line**, almost universally **64 bytes** on modern hardware.

Analogy: a librarian who refuses to bring you one page and will only bring the whole shelf.

Consequences that follow directly:

- If you need 4 bytes, you still pay for 64 bytes of transfer.
- If the *next* thing you need is within those 64 bytes, it's free — the fetch already happened.
- If the next thing is somewhere else in memory, you pay the full ~100 ns again.

So the question that governs performance is:

> **Is the next thing I need already on the shelf I'm holding?**

This is called **spatial locality**. Data laid out contiguously has it. Data scattered across the heap and linked by references does not.

### 1.3 The prefetcher, and why pointer-chasing defeats it

The CPU has a **hardware prefetcher**: it watches your access pattern and, if it detects a regular stride (like walking an array), it starts fetching future lines *before* you ask for them. Done well, this hides DRAM latency completely.

The prefetcher needs to be able to **predict the next address**. When you walk a linked structure:

```
node = node.next;      // the address of the next node is INSIDE the current node
```

...the CPU cannot know where to go next until the current fetch completes. These are **dependent loads**, and they **serialise**: 100 ns, then 100 ns, then 100 ns, with no overlap. With an array, the misses **overlap** — several in flight at once.

This is why the measured penalty (Section 5) is *worse* than raw DRAM latency would suggest.

### 1.4 The TLB — the second, hidden locality tax

Your program uses **virtual addresses**; the hardware must translate each one to a **physical address**. That translation is itself cached, in the **Translation Lookaside Buffer (TLB)** — typically a few hundred to ~1500 entries, each covering one 4 KiB page.

- A 64 KiB stack spans ~16 pages → 16 TLB entries → always resident.
- A 500 MB heap spans ~128,000 pages → far more than the TLB holds → misses.

A TLB miss triggers a **page walk**: extra dependent memory accesses just to find out where your data physically lives, *before* you can even fetch it.

So scattered data pays **twice** — once for the cache miss, once for the translation miss.

---

## 2. The complete storage taxonomy

"Stack vs heap" is a simplification. There are six classes, and knowing all six prevents several common mistakes.

| Class | Lifetime | Runtime cost | Where it physically lives |
|---|---|---|---|
| **Registers** | Expression-level, compiler-chosen | Zero | Inside the CPU |
| **Stack (automatic)** | Lexical scope, strictly LIFO | ~Zero | Per-thread stack region |
| **Static / global** | Whole program run | Zero at runtime | `.data` (initialised), `.bss` (zero-filled), `.rodata` (read-only) |
| **Thread-local (TLS)** | Thread lifetime | One extra segment-register dereference (`%fs:`) | Per-thread TLS block |
| **Heap (dynamic)** | Arbitrary — you or the GC decide | High | `brk` region + `mmap` regions |
| **Code / mapped files** | Program or mapping lifetime | Page fault on first touch only | `mmap`'d, demand-paged, shared via page cache |

### 2.1 Two things commonly misstated

**`.bss` is free until touched.** A 1 GB zero-initialised global array occupies **zero bytes on disk** and costs nothing to "allocate" — the kernel maps a shared zero page copy-on-write. Real memory is only committed when you write to it.

**Large heap allocations aren't in the heap.** Allocators route big requests to their own `mmap` region, bypassing the free lists entirely. In glibc the default threshold is around 128 KiB; such a block is returned to the OS immediately on `free()` via `munmap()`. Practical upshot: freeing one big buffer returns memory to the OS; freeing a million small objects usually does not.

---

## 3. Why the stack is essentially free

The stack is **ordinary DRAM**. There is no special fast memory. Its speed comes entirely from a *discipline*: **LIFO lifetimes**.

Three consequences follow, and together they are the whole explanation:

**1. Allocator state collapses to one register.** Because lifetimes nest perfectly, "what's free" is answerable by a single pointer — the stack pointer, `rsp`. No free lists, no search, no bookkeeping.

**2. Addresses are compile-time constants.** Every local's location is `rsp + fixed offset`. No runtime lookup.

**3. No synchronisation.** Each thread has its own stack. Zero contention, zero locking, ever.

### 3.1 What the compiler actually emits

```asm
f:                          ; long f(long i){ long buf[8]; use(buf); return buf[i&7]; }
        subq    $80, %rsp   ; <-- allocate the ENTIRE frame: ONE instruction
        ...
        movq    (%rsp,%rbx,8), %rax
        addq    $80, %rsp   ; <-- deallocate the ENTIRE frame: ONE instruction
        ret

g:                          ; leaf function, no locals needing memory
        leaq    (%rdi,%rdi,2), %rax
        ret                 ; <-- NO frame at all; everything stayed in registers
```

Two lessons from this:

- The `sub`/`add` pair is **amortised across every local in the frame**. Ten locals cost the same one instruction as one local.
- **The cheapest storage isn't the stack — it's registers.** `g` never touched memory. Additionally, the x86-64 System V ABI defines a **red zone**: 128 bytes below `rsp` that leaf functions may use with *no* stack-pointer adjustment at all.

### 3.2 The stack's limitation is exactly what makes it fast

You **cannot return a reference to a frame that is about to be popped**. The moment a value must outlive its creating scope, LIFO discipline breaks and the stack cannot hold it.

> **Every heap allocation in every language exists to escape that one constraint.**

This is the correct way to think about heap allocation: not as "the slow option," but as "the option you take when lifetime is not lexical."

---

## 4. Why the heap costs more — the four separate costs

"The heap is slow" collapses four independent costs that have very different magnitudes. Separating them is most of the value in this whole topic, because **the cost people name first is the smallest one.**

| # | Cost | Stack | Heap |
|---|---|---|---|
| 1 | **Allocation** | `sub rsp, N`, amortised | Free-space search, size-class lookup, possibly a lock |
| 2 | **Reclamation** | `add rsp, N` on return | Free-list insert + coalescing, or GC: tracing + barriers + sweeping |
| 3 | **Access locality** | Hot end permanently in L1, 1–2 TLB entries | Arbitrary addresses → cache misses, TLB misses, pointer chasing |
| 4 | **Metadata** | Zero per object | Chunk/object headers, alignment padding, mark bits |

### 4.1 The magnitudes are not comparable

Measured on my machine (details in Section 5):

- **Cost 1 (allocation): ~8×.** Real, but modern allocators have nearly closed this gap.
- **Cost 3 (locality): ~51×.** Imposed by hardware. No allocator can fix it.

**Therefore: the real answer to "why is heap data slower" is cost 3, not cost 1.**

And the crucial reframing:

> Heap-resident data isn't slow *because it's on the heap*. It's slow because **heap layout is chosen by an allocator optimising for space reuse, not by you optimising for traversal order.**

This also explains why the penalty applies to `malloc`'d C with no garbage collector anywhere in sight. "Heap vs stack" is a *proxy* for the real variable, which is **layout**.

### 4.2 Cost 4 in practice — why tiny objects are expensive

Every heap object carries metadata:

| Runtime | Per-object overhead |
|---|---|
| glibc `malloc` | 8–16 B chunk header; **minimum chunk 32 B on 64-bit** |
| Java (classic) | 12 B header (mark word + class pointer), padded to 16 B |
| Java (compact headers, JDK 24+) | 8 B |
| Python | 16 B minimum (`ob_refcnt` 8 + `ob_type` 8), before payload |
| Go | 0 per object — type info lives in the span, not the object |

Concrete consequence: **ten million 8-byte values stored as objects cost ~300 MB, not 80 MB.** In C, `new int` costs 32 bytes, not 4.

This is the arithmetic behind the rule *"fewer, bigger objects beat many tiny ones."*

---

## 5. Measured evidence

All figures measured on the machine in Appendix A.

### 5.1 Allocation cost and locality cost, isolated (C)

```
stack frame (64B)           :   1.37 ns/op
malloc+free, LIFO reuse     :  11.35 ns/op      <- ~8x
malloc+free, 64 live chunks :  11.07 ns/op

walk 2M nodes, contiguous   :   3.36 ns/node
walk 2M nodes, scattered    : 172.28 ns/node    <- ~51x
```

**How the locality test was constructed** (this matters, or the number means nothing): identical data, identical node count (2 million), identical total size (32 MB). The *only* difference is address layout — one is an array traversed via `next` pointers; the other is the same nodes individually `malloc`'d and then linked in a random permutation, which is what a linked structure built up over time actually looks like.

172 ns/node exceeds raw DRAM latency because of the compounding described in Sections 1.3 and 1.4: dependent loads serialise instead of overlapping, and TLB misses stack on top of cache misses.

### 5.2 A methodological warning worth internalising

My **first** version of this benchmark reported malloc at **1.28 ns/op** — suspiciously close to the stack. Checking the generated assembly showed why:

```asm
heap_alloc:
        movq    %rdi, %rax
        ret                     ; the malloc/free pair was DELETED entirely
```

GCC proved the allocation was unobservable and removed it.

> **Rule: any allocation microbenchmark that doesn't check the disassembly is measuring nothing.** In Java the equivalent hazard is dead-code elimination by C2 — which is exactly why JMH exists.

### 5.3 JVM allocation, with the optimisations switched off

```
                                  non-escaping    escaping
default (EA on, TLAB on)              3.54          4.91  ns/op
-XX:-DoEscapeAnalysis                 4.51          4.77  ns/op
-XX:-UseTLAB                          2.38         48.11  ns/op
-XX:-DoEscapeAnalysis -XX:-UseTLAB   46.23         48.19  ns/op
```

Read the third row carefully — it's the informative one. With TLABs disabled, the *non-escaping* case got **faster** (2.38 ns). Why? Because escape analysis had **eliminated the allocation entirely**, so TLAB state was irrelevant. The escaping case had to CAS into shared Eden and paid 48 ns.

**Conclusion: escape analysis and TLABs are two independent layers.** With both removed, Java heap allocation costs roughly **20×** what an eliminated allocation costs. With both present, it's ~4 ns — cheap enough that avoiding `new` for performance reasons is essentially always wrong.

### 5.4 Python allocation and the interpreter-overhead surprise

```
bare object()                                  40.0 ns/op
tuple (a,b)                                    26.6 ns/op
list [a,b]                                     41.2 ns/op
small int add (result cached, -5..256)         12.8 ns/op   <- no allocation
large int add (fresh heap object)              24.7 ns/op
float add (fresh heap object)                  17.4 ns/op

sum(list of boxed ints)                         7.2 ns/elem
sum(array.array('q'), contiguous int64)        13.6 ns/elem  <- SLOWER
```

The last line is an **honest counter-result** and worth remembering. The contiguous `array.array` was *slower* than the scattered list of boxed integers, because each raw `int64` must be **boxed into a fresh `PyObject`** before the interpreter can touch it.

**Lesson: in CPython, interpreter dispatch overhead is large enough to mask the locality effect entirely.** This is precisely why the fix for numeric Python isn't better layout *inside* Python — it's NumPy, which moves the loop into C where the layout can actually pay off.

Object sizes measured via `sys.getsizeof`:

```
object()   16 B      list []    56 B
int 1      28 B      dict {}    64 B
float      24 B      tuple ()   40 B
big int    40 B      str ''     41 B
```

---

## 6. How each language allocates

### 6.1 C++ — explicit storage duration

The standard defines exactly four storage durations: **automatic, static, thread, dynamic** (`[basic.stc]`). `new` maps to `operator new`, which typically calls `malloc`.

**glibc ptmalloc2 allocation path, in order:**

1. **tcache** — per-thread cache, an array of 64 singly-linked lists (one per small size class), default 7 chunks each. Because it's thread-local, push/pop requires **no locking**. This is the fast path and where most allocations are served.
2. **fastbins** — LIFO singly-linked lists, no coalescing, default max chunk 128 B.
3. **unsorted bin → small bins / large bins** — best-fit search with splitting.
4. **top chunk** — carve from the "wilderness"; extend with `brk()`.
5. **mmap** — requests above the mmap threshold (~128 KiB, dynamically adjusted) get a dedicated mapping, skipping bins entirely.

**Threading model:** one **main arena** plus multiple **thread arenas**. glibc increases the arena count under contention and **never decreases it**. The main arena can use both `sbrk` and `mmap`; thread arenas use `mmap` only, taking `HEAP_MAX_SIZE` (64 MB on 64-bit) at a time. Default cap is 8 × cores on 64-bit; tunable via `MALLOC_ARENA_MAX`.

**Levers:** RAII + value semantics (keep it on the stack), `reserve()`, `std::pmr::monotonic_buffer_resource` over a stack array, small-buffer optimisation, struct-of-arrays layout, or swapping in tcmalloc / jemalloc / mimalloc.

### 6.2 Java — the allocation cost has been engineered away

**Runtime data areas** (JVM Spec §2.5):

| Area | Scope | Contents |
|---|---|---|
| pc register | Per-thread | Current instruction address |
| JVM stack | Per-thread | Frames: locals, operand stack, frame data |
| Native method stack | Per-thread | For native calls |
| **Heap** | **Shared** | All objects and arrays |
| **Method area** (+ run-time constant pool) | **Shared** | Class metadata, static fields, code |

Note: the spec *permits* frames to be heap-allocated. HotSpot doesn't do this, but "stack" here is a **specification concept**, not a hardware guarantee.

**Allocation is a pointer bump.** A global lock on Eden would be a bottleneck, so HotSpot gives each thread a **TLAB (Thread-Local Allocation Buffer)** — its own chunk of Eden. Allocation is then: bump a pointer, no locking. Only **refilling** a TLAB — infrequent — needs to be thread-safe.

Important subtlety: **TLABs are thread-local only in the temporal sense.** They remain part of the shared Java heap, and a thread can freely publish a reference to a newly allocated object outside its own TLAB.

**Escape analysis** sits above this. If C2 can prove an object never escapes its method, it applies **scalar replacement** — the object is dismantled into individual fields held in registers, and the allocation never happens. This is what produced the 2.38 ns figure in Section 5.3.

**Where Java actually loses:** not allocation. It's (a) GC — tracing scales with the **live set**, plus write barriers on every reference store; and (b) **mandatory indirection** — `Integer[]` is an array of *pointers* to scattered 16-byte objects, while `int[]` is contiguous. That's the 3.36 vs 172 ns result wearing Java clothing. Project Valhalla exists to fix exactly this.

### 6.3 Python — no stack allocation at all

CPython has **no user-visible automatic storage.** Every value is a heap `PyObject`, minimum 16 bytes of header (`ob_refcnt` + `ob_type`) before any payload.

**pymalloc's three levels** — arena → pool → block:

| | Widely-quoted (stale) | Actual, 64-bit, CPython 3.12 / 3.14 / main |
|---|---|---|
| Arena | 256 KiB | **1 MiB** (`ARENA_BITS 20`); 2 MiB with hugepages |
| Pool | 4 KiB | **16 KiB** (`POOL_BITS 14`, requires the radix tree) |
| Alignment | 8 B | **16 B** |
| Size classes | 64 | **32** (`512 / 16`) |
| Small-request threshold | 512 B | 512 B ✓ |

*(Verified by reading `Include/internal/pycore_obmalloc.h` directly. Large arenas/pools are enabled when `SIZEOF_VOID_P > 4`, i.e. all 64-bit builds. Most online write-ups still quote the pre-3.10 figures.)*

Anything over 512 bytes bypasses pymalloc and goes to the system allocator. Arenas come from `mmap` and are **only released when every pool inside is free** — which rarely happens. **This is why Python processes grow and essentially never shrink.**

**The real Python tax is reference counting.** Every read of an object *writes* to its refcount — dirtying the cache line, defeating cross-core sharing, and turning read-only traversals into stores. Two mitigations have shipped: **PEP 683 immortal objects** (3.12) exempt `None`/`True`/small ints; and **mimalloc** is now the default and required allocator for object domains in the **free-threaded build**, using per-thread heaps so most allocation/deallocation proceeds without locking.

### 6.4 Go — the compiler decides, and tells you

Go has no `new` vs. stack distinction in the *language*. The compiler decides, per the official FAQ: it allocates a local in the stack frame **when it can prove the variable is not referenced after the function returns**; if it cannot prove that, it must heap-allocate to avoid a dangling pointer. A very large local may go to the heap regardless.

> **Key insight: escape analysis is a safety proof, not an optimisation.** When it cannot prove locality, it conservatively heaps. Your job is to make the proof easy.

**Common escape triggers:** returning a pointer to a local; storing into an `interface{}`; capture by a closure or goroutine; size unknown at compile time.

**Goroutine stacks** start at `stackMin = 2048` bytes and grow by allocating a larger stack, copying, and rewriting every pointer into the old one. Cheap goroutines are cheap *because* the stack is tiny — but deep recursion pays repeated copy costs.

**Heap allocator** (tcmalloc-derived), four structures:

| Structure | Role |
|---|---|
| `mcache` | Per-P cache of spans with free slots — **lock-free fast path** |
| `mcentral` | All spans of one size class; amortises locking |
| `mspan` | A run of pages serving one size class |
| `mheap` | Page-granularity (8192 B) management; requests ≥1 MB from the OS |

Small sizes up to and including **32 kB** are rounded to one of ~70 size classes (`_NumSizeClasses = 68`). Objects >32 kB bypass mcache and mcentral entirely.

Go's GC is **concurrent mark-sweep and non-moving** — no compaction, so addresses never change, but also no defragmentation. Fragmentation is contained by size classes instead.

---

# PART II — GARBAGE COLLECTION

The two languages differ at the root, and everything else follows from that single choice:

> **Python counts references. Java traces from roots.**

---

## 7. Python: reference counting + cycle detection

### 7.1 Layer one — reference counting (handles ~95% of everything)

Every `PyObject` carries a **reference count** in its header. Every assignment, argument pass, or container insert increments it (`Py_INCREF`); every scope exit, reassignment, or `del` decrements it (`Py_DECREF`). **When it reaches zero, the object is freed immediately** — no collector involved, no pause, fully deterministic.

Demonstrated:

```
A) no cycle -> refcount frees it INSTANTLY, gc never involved
    refcount: 1
    __del__ ran for plain          <- ran ON `del a`, not later
```

**This determinism is a genuine advantage.** It's why `with` blocks and `__del__` are broadly reliable in Python and why files close promptly. Java offers no equivalent guarantee.

### 7.2 The flaw — reference cycles

```python
x.ref = y
y.ref = x
del x, y        # both names gone; each object still holds a reference to the other
```

Neither count can ever reach zero. The memory is **unreachable but immortal**. Reference counting cannot solve this on its own — hence layer two.

### 7.3 Layer two — the generational cycle detector (`gc` module)

**Only container objects are tracked.** Lists, dicts, tuples, class instances — anything capable of holding a reference. Ints, floats and strings are **never tracked**, because they cannot participate in a cycle. This is why the cycle detector is cheaper than it sounds.

**Three generations**, default thresholds `(700, 10, 10)` (verified via `gc.get_threshold()`):

- Gen 0 is collected when *(allocations − deallocations)* exceeds **700**
- Every **10** gen-0 passes triggers gen 1
- Every **10** gen-1 passes triggers gen 2

**The algorithm** (worth understanding, it's elegant):

1. Copy each candidate's refcount into a scratch field (`gc_refs`).
2. Traverse all candidates, **subtracting the references they hold to each other**.
3. Anything left with a **positive** count is referenced from *outside* the candidate set → it's alive. Mark it, and everything reachable from it.
4. The remainder is a cycle with no external references → free it.

**Properties:** stop-the-world (the GIL is held), **non-moving**, **non-compacting**. Memory is never defragmented.

### 7.4 Current status — actively unsettled

Python 3.14 shipped a new **incremental** garbage collector. After reports of significant memory pressure in production, it was **reverted in both 3.14 and 3.15**, returning to the 3.13 generational GC. Worth knowing so you don't cite the incremental design as current.

---

## 8. Java: tracing only

There is **no reference count anywhere** in a Java object header. Instead the JVM starts from **GC roots** — thread stacks, static fields, JNI references — and marks everything reachable. Anything not marked is garbage **by omission**.

### 8.1 The single most important consequence

> **GC cost is proportional to the LIVE SET, not to the garbage.**

Dead objects are **never touched**. A million objects that die immediately cost approximately nothing to collect. This is the fact that drives every practical Java memory rule in Part IV.

Cycles are a **non-issue**: an unreachable cycle simply isn't marked. No special handling required.

### 8.2 Generational layout

Built on the **weak generational hypothesis**: *most objects die young.*

```
   new objects              survivors, age+1            age >= 15
  ┌───────────┐   minor GC  ┌──────────────┐  promotion  ┌──────────────┐
  │   Eden    │ ──────────> │  Survivor    │ ──────────> │      Old     │
  └───────────┘             │  S0 / S1     │             │  generation  │
        │                   └──────────────┘             └──────────────┘
        │ most objects unreachable here
        v
    reclaimed at ~zero cost (never visited)
```

After a minor GC, **Eden is reset by moving a pointer back to the start** — which is precisely why allocation can be a pointer bump (Section 6.2). The two facts are the same fact.

Real log from my run:

```
[gc] GC(2) Pause Young (Allocation Failure) 22M->5M(61M) 1.463ms
[gc] GC(3) Pause Young (Allocation Failure) 22M->5M(61M) 1.402ms
```

**17 MB reclaimed in 1.4 ms** — fast because the collector only ever touched the 5 MB that survived.

### 8.3 Three mechanisms worth naming

**Write barrier + card table.** To avoid scanning the entire old generation looking for references *into* the young generation, the JVM instruments every reference store to mark a "card" dirty. Minor GC then scans only dirty cards. **This is a small, permanent tax on every `obj.field = other` in your code** — and it's why primitive fields are cheaper than reference fields beyond just size.

**Copying is compaction.** Because survivors are *moved*, the heap is defragmented for free. Python, being non-moving, can never do this.

**Collector choice:**

| Collector | Character |
|---|---|
| Serial | Stop-the-world, single-threaded. Default on small/1-core machines |
| Parallel | Stop-the-world, multi-threaded. Throughput-oriented |
| **G1** (default) | Heap split into ~2048 regions; collects highest-garbage regions first; targets a pause goal |
| **ZGC** | Colored pointers + load barriers; relocates concurrently; **sub-millisecond pauses independent of heap size** |

ZGC now runs in **generational mode by default**; the non-generational mode was **removed in JDK 24 (JEP 490)**.

*Field note:* my benchmark run reported `Using Serial`, not G1, because the container has 1 CPU. **The JVM picks the collector ergonomically from core count and memory** — know this before a GC log confuses you.

---

## 9. Side-by-side, and what it changes in your code

| | Python | Java |
|---|---|---|
| Primary mechanism | Reference counting | Tracing from GC roots |
| Cycles | Need a separate detector | Handled inherently |
| Cost scales with | Every INCREF/DECREF + live containers during a pass | **Live set only** |
| Deterministic release | **Yes** (non-cyclic objects) | **No** |
| Pause | Short stop-the-world at thresholds | ~1 ms (G1) to sub-ms (ZGC) |
| Moves objects | **Never** | Yes (G1, ZGC) |
| Fragmentation | Yes, permanent | Compacted away |
| Header | 16 B, includes the count | 12 B → 8 B (JEP 519) |
| Multithreading | GIL serialises; free-threaded builds use biased refcounting | Fully concurrent phases |
| Returns memory to OS | Reluctantly — arena must be entirely empty | Yes |
| Main knobs | `gc.set_threshold`, `gc.freeze`, `gc.disable` | `-Xmx`, `-XX:+UseZGC`, `MaxGCPauseMillis` |

### 9.1 Rules that follow — Java

- **Never rely on `finalize()`.** Deprecated, and may never run. Use `try-with-resources` / `AutoCloseable`.
- **Allocation is cheap; retention is expensive.** Every unbounded cache directly inflates GC cost. This is "cost scales with the live set," restated as an engineering rule — and it's exactly the point to make when an interviewer probes your caching design.
- **Never call `System.gc()`.** It's a hint, and it usually triggers a full collection you didn't want.

### 9.2 Rules that follow — Python

- **`with` and `__del__` are broadly reliable**, because refcounting is deterministic. This is a real advantage over Java.
- **`gc.freeze()` before forking** is a genuine production technique: it moves existing objects into a permanent generation so the collector stops touching them, keeping copy-on-write pages shared with the child.
- **`gc.disable()` is occasionally correct** if you're confident you create no cycles. Refcounting keeps working; you only lose cycle cleanup.

---

# PART III — CONCURRENCY

## 10. Platform threads vs virtual threads

### 10.1 The problem

Since Java 1.2, each Java thread runs on a **platform thread** supplied by the OS. Per dev.java, platform threads have nontrivial costs: **a few thousand CPU instructions to start, and a few megabytes of memory each.**

So a server cannot give every request its own platform thread. And in a typical server application, those requests **spend most of their time blocking** — waiting on a database or a downstream service. The threads sit idle, consuming megabytes, doing nothing.

The old remedy was non-blocking APIs and callbacks. dev.java's own verdict: *this gets unpleasant quickly, as the callbacks nest ever more deeply.*

### 10.2 The mechanism

Introduced by JEP 425 in Java 19; preview in 20 (JEP 436); **final in Java 21**.

**Many virtual threads run on one platform thread.** When a virtual thread blocks, it is **unmounted**, and the platform thread — called the **carrier thread** — immediately runs a different virtual thread.

```
  runs on carrier  ──>  blocks on I/O  ──>  unmounted  ──>  carrier picks up another VT
```

The task waits; **the carrier thread does not.** A platform thread would have sat idle for the entire wait.

*(The name is deliberately reminiscent of virtual memory mapped onto actual RAM.)*

The payoff is a **programming-model** one: with virtual threads, blocking is cheap, so you simply block — using branches, loops and try blocks instead of a callback pipeline.

### 10.3 Measured

```
A) 1000 tasks, each BLOCKS 100ms (simulated network I/O)
  fixed pool of 16 platform threads    6328 ms
  virtual thread per task               143 ms      <- 44x

B) 200 tasks, each BURNS CPU (no blocking at all)
  fixed pool of 16 platform threads     314 ms
  virtual thread per task               257 ms      <- no meaningful difference
```

**Case A is 44× not because virtual threads are faster**, but because the 16 platform threads spent 6.3 seconds *sitting idle inside `sleep`*. Virtual threads let all 1000 waits overlap.

**Case B is the point of Section 11.**

---

## 11. Concurrency vs parallelism — the distinction that decides everything

dev.java states flatly: *virtual threads offer no benefit for CPU-intensive tasks.* This is not a caveat — it is a **direct consequence of the mechanism**, and understanding why is the whole lesson.

**Two reasons:**

1. The entire trick is **unmounting on block**. CPU-bound work **never blocks**, so it **never unmounts**. The mechanism simply doesn't engage.
2. The ceiling is fixed: **by default there are exactly as many carrier threads as CPU cores** (tunable via `jdk.virtualThreadScheduler.parallelism`).

So for pure computation you have N carrier threads on N cores — **exactly what a fixed pool of size N already gives you.** You've added a scheduling layer and a continuation stack per task, and bought nothing.

> **The mental model:**
> **Virtual threads increase CONCURRENCY** — how many tasks can be *in flight*.
> **They do nothing for PARALLELISM** — how many can *execute at once*.
>
> Blocking I/O is limited by concurrency. Computation is limited by parallelism.

*(Honest caveat on my Case B measurement: the container reports `availableProcessors = 1`, so parallelism was capped at 1 regardless. That is itself the point — **virtual threads cannot manufacture cores.**)*

---

## 12. Parallel streams and Fork/Join

These target **parallelism** directly — the thing virtual threads don't address.

### 12.1 How parallel streams work

Parallelisation in the Stream API is **recursive decomposition**, built on the **Fork/Join Framework** (JDK 7). The data is divided in two; each part is processed by its own core, which may divide it again, recursively, until a part is small enough to process normally. Partial results are then merged back up.

**Scheduling:** the **Common Fork/Join Pool** is created at application launch, with thread count aligned to core count. It adds **work stealing** — an idle thread examines other threads' waiting queues, takes a task, and runs it — which keeps every core busy even when the split is uneven.

`parallelStream()` is the packaged version. `RecursiveTask` is the same engine exposed directly, for when your problem isn't a collection (tree traversal, matrix work, recursive search).

### 12.2 dev.java's cautions — take these seriously

- **A parallel stream is not always faster than a sequential one.**
- **Going parallel consumes threads.** In a webserver, those threads serve HTTP requests — are you willing to take them for something else?
- Stateful operations (`limit`, `skip`, `findFirst`) need encounter order, which means **shared mutable state across threads** — costly.
- **Mutating state external to the stream is an antipattern.** Adding to a plain `ArrayList` from a parallel stream produces missing elements or an `ArrayIndexOutOfBoundsException`.

**The four rules, as stated:**

1. Do not optimise because it's fun; optimise because you have requirements you do not meet.
2. Choose your source of data with caution.
3. Do not modify external state, and do not share mutable state.
4. Do not guess; measure.

### 12.3 The locality connection

The same dev.java page makes the Part I argument independently — Oracle's own docs, not just my benchmark. It walks `int[]` vs `Integer[]` vs `LinkedList<Integer>` and concludes that following references to reach your data is **pointer chasing**, that it should be avoided, and that it is the main performance hit when iterating a linked list of `Integer`.

And it matters *for parallelism* too:

| Source | Splittability |
|---|---|
| `ArrayList` | **Perfect** — middle element is free, sizes exactly known |
| `IntStream.range` | Perfect — like an array of numbers |
| `TreeSet` | Even split (red-black tree balances), but still pointer chasing |
| `HashSet` | Splits the bucket array, but sub-parts may be uneven or empty |
| `LinkedList` | **Poor** — reaching the middle means walking half the list, one node at a time |
| `Files.lines`, `Stream.generate`/`iterate` | Poor — size unknowable in advance |

**Bad locality makes splitting expensive.** Layout and parallelism are the same problem.

### 12.4 The decision rule

| Workload | Use |
|---|---|
| Thousands of tasks, each waiting on network/DB | **Virtual threads** (`Executors.newVirtualThreadPerTaskExecutor()`) |
| One big computation over a splittable collection | **`parallelStream()`** |
| Recursive computation that isn't a collection | **`RecursiveTask`** / Fork-Join |
| A handful of long-running background workers | Plain platform threads or a fixed pool |

---

## 13. Pinning, thread locals, rate limiting

Three traps flagged on the same dev.java page.

### 13.1 Don't pool virtual threads

dev.java is direct about this: scheduling tasks on virtual threads that are then scheduled on platform threads is *clearly inefficient*. And the rhetorical point lands — if you're limiting virtual threads to the small number of concurrent requests your service tolerates, **why are you using virtual threads at all?**

**Instead:** protect each constrained resource appropriately. Use a `Semaphore` around the limited resource. Blocking on `acquire()` is fine — **blocking is cheap now.** For database connections, the connection pool likely already does the right thing.

### 13.2 Pinning

A virtual thread is **pinned** — unable to unmount — in two situations:

1. Executing a `synchronized` method or block *(true in JDK 21, 22, 23)*
2. Calling a native method or foreign function

**Being pinned isn't bad in itself.** The problem is a pinned thread that *blocks*: it cannot be unmounted, so **the carrier thread is blocked too**, and in Java 21 no additional carrier thread is started. Fewer carriers remain to run everything else.

**Mitigation:** replace `synchronized` with `ReentrantLock` around blocking calls. Pinning is harmless if `synchronized` only guards an in-memory operation.

**Fixed in JDK 24 and later.**

**Diagnosis:** `-Djdk.tracePinnedThreads` (one warning per pinning location), or Java Flight Recorder → look for `VirtualThreadPinned` and `VirtualThreadSubmitFailed` events.

> **LLD connection:** this makes `synchronized` vs `ReentrantLock` a **correctness-adjacent** decision on modern JDKs, not merely a style one.

### 13.3 Thread locals

There will be **far more virtual threads than pooled platform threads**, so a `ThreadLocal` that previously had 200 instances may now have 200,000. dev.java's advice: rethink your sharing strategy. Trace usage with `-Djdk.traceVirtualThreadLocals`.

### 13.4 Other virtual-thread API facts

All virtual threads: are in a **single thread group**, have **`NORM_PRIORITY`**, and are **daemon threads**. `setPriority` and `setDaemon` have **no effect**. `Thread.getAllStackTraces()` returns **platform threads only**. `Thread.isVirtual()` tests for virtualness. There is **no way to find the carrier thread** a virtual thread runs on.

---

# PART IV — PRACTICAL APPLICATION

## 14. The six rules

Everything in Parts I–III compresses to this. If you retain nothing else, retain this section.

**The framing:** you control exactly two things, and everyone worries about the wrong one.

| | Who handles it | Should you care? |
|---|---|---|
| Cost of creating an object | The runtime — already very good at it | **No.** Never contort a design to avoid `new`. |
| **How many objects stay alive** | **You** | **Yes.** This is what GC pauses are made of. |
| **How your data is laid out** | **You**, via data-structure choice | **Yes.** This is the ~50× lever. |
| Header / padding per object | The runtime | Only with millions of tiny objects |

> **Keep the live set small. Keep related data together.** Everything below is a corollary.

### The rules

**1. Prefer contiguous over linked.**
`ArrayList` over `LinkedList`. Arrays over pointer chains. `vector` over `std::list`.
Almost always — **even when Big-O says otherwise.** An O(n) scan over a packed array routinely beats an O(1) linked-list insert, because the constant factor is ~50×.

**2. Allocate outside the loop.**
Build the buffer once, reuse it. The single highest-value habit on this list.

**3. Let garbage die young.**
Short-lived temporaries are nearly free (generational hypothesis). Long-lived caches are expensive. **Bound every cache you write.**

**4. Don't box primitives on hot paths.**
`int[]`, not `List<Integer>`, when it's numeric and large.

**5. Fewer, bigger objects beat many tiny ones.**
Header + padding is per-object. Ten million 8-byte values as objects ≈ 300 MB, not 80 MB.

**6. Measure before you change anything.**
Every rule above loses to a profiler. And check the disassembly — see Section 5.2.

---

## 15. Per-language cheat sheet

### Java

- **Allocation is a pointer bump in your thread's own TLAB — about 4 ns. Never avoid `new` for performance.** The JIT frequently deletes the allocation entirely when the object doesn't escape.
- **The real cost is indirection.** `Integer[]`, `List<Integer>`, `ArrayList<Point>` are arrays of *pointers* to scattered objects. `int[]` / `long[]` are the packed version.
- `StringBuilder` in loops. `ArrayList` over `LinkedList`. Size your collections: `new ArrayList<>(expectedSize)`.
- Bound your caches — retention, not allocation, is what costs.

### Python

- **You cannot avoid the heap.** Every int, string and object is a heap object with a 16-byte header. Don't fight it.
- **The lever is doing fewer Python-level operations:** comprehensions over manual loops; built-ins (`sum`, `sorted`, `join`) over hand-rolled loops; generators instead of building large intermediate lists.
- `__slots__` on classes instantiated in the millions (removes the per-instance `__dict__`).
- **NumPy when the loop is numeric** — that's rule 1, applied correctly, by moving the loop into C.

### C++

- **Values by default**; pass big things by `const&`. RAII means the compiler frees for you, so stack allocation costs nothing.
- `vector::reserve()` before filling. `vector` over `list`, and often over `map` for small N.
- **Struct-of-arrays** instead of array-of-structs when you loop over one field.

### Go

- **The compiler decides stack vs heap for you.** `go build -gcflags='-m'` prints exactly what escaped. **This is the single best learning tool of the four languages** — immediate feedback on your own code.
- Preallocate: `make([]T, 0, n)`.
- **Fewer pointer fields per struct = less work for the GC.**

---

## 16. Where this shows up in LLD

This is the part that earns marks. The rest is background.

### 16.1 Two GoF patterns are literally memory patterns

**Flyweight.** Split state into **intrinsic** (shared, immutable) and **extrinsic** (per-use, passed in), so you create ten objects instead of ten million.
*Classic cases:* characters in a text editor, tiles in a game map, chess pieces.
**If asked to design a text editor or game board, this is the expected answer.**

**Object Pool.** Reuse objects that are expensive **to create** — database connections, threads, sockets.
**The trap:** pooling plain data objects. Those are cheap to create, and pooling them keeps them alive forever, which makes GC *worse*, not better.

> Being able to say *"I'd pool connections but not DTOs"* is a strong signal — it shows you understand that GC cost tracks retention.

### 16.2 Justifying collection choices in the class diagram

This is where it actually appears — not by reciting cache lines, but in one sentence:

- *"`ArrayList` here — this is read-heavy with indexed access, and we rarely insert in the middle."*
- *"`ConcurrentHashMap` rather than a synchronised map — reads dominate and I don't want a single lock across the whole structure."*
- *"I'm holding IDs rather than full objects in this index, so the cache doesn't pin the whole object graph in memory."*

And the most valuable one, when pushed on performance:

- *"I'd measure that before optimising it — my instinct is that the bottleneck is elsewhere."*

### 16.3 Calibration — be honest about the weight

LLD rounds grade **correctness, SOLID, extensibility, and thread safety.** Performance is a **tiebreaker** where you demonstrate you understand the trade-off.

> **Nobody loses an LLD round for not knowing TLABs. People gain ground by choosing `ArrayList` deliberately and being able to say why in one line.**

Everything in Parts I–III is **out of scope** for a machine coding round. It's worth knowing; it is not worth prep time during a sprint.

---

## 17. Java ↔ Python mapping

For translation notes only. **Design transfers; syntax doesn't.** Solving LLD problems twice, once per language, doubles the workload for near-zero gain.

| Java | Python |
|---|---|
| `interface` | `abc.ABC` + `@abstractmethod`, or `typing.Protocol` (structural) |
| `abstract class` | `ABC` with some concrete methods |
| `enum` with fields/methods | `enum.Enum` — methods and attributes work the same |
| `record` | `@dataclass(frozen=True)` |
| `final` field | frozen dataclass |
| `Optional<T>` | `T \| None` |
| `Comparator` | `key=` argument, or `functools.total_ordering` |
| `PriorityQueue` | `heapq` |
| `ArrayDeque` | `collections.deque` |
| `LinkedHashMap` (LRU) | `collections.OrderedDict`, or `functools.lru_cache` |
| `EnumMap` | plain `dict` |
| **`TreeMap`** (`floorKey`/`ceilingKey`) | **No stdlib equivalent** — `bisect` on a sorted list, or `sortedcontainers.SortedDict` |
| `BlockingQueue` | `queue.Queue` |
| `ExecutorService` | `concurrent.futures.ThreadPoolExecutor` |
| `synchronized` | `with lock:` |
| `ReentrantLock` | `threading.RLock` |
| `AtomicInteger` | counter guarded by a lock |
| Singleton | **module-level instance — modules already are singletons** |

**Two entries worth internalising beyond the table:**

1. **`TreeMap` has no clean Python answer.** This matters for interval, booking and scheduling problems.
2. **Singleton is a non-pattern in Python.** A module already is one. Interviewers who know Python expect you to say this.

### 17.1 The one Python concurrency fact that matters at work

**The GIL does not make your code thread-safe.** It serialises bytecode execution, but **check-then-act still races**:

```python
if seat_id not in booked:      # thread A and thread B can BOTH pass this check
    booked[seat_id] = user     # both write; one silently overwrites the other
```

**Fix:** an explicit `threading.Lock`, or `dict.setdefault()`, which is atomic.

This is the most common Python concurrency bug, and it is the exact same **check-then-act** failure as the Java seat-booking case — see Section 18.

---

## 18. Interview-ready statements

Compressed to what you'd actually say out loud.

### Heap vs stack

> "The stack is fast because lifetimes are LIFO, so the allocator is a single pointer and the working set stays in L1. The heap's allocation cost is small on modern runtimes — the real cost is losing spatial locality, because layout is chosen by the allocator rather than by traversal order. That penalty applies to `malloc`'d C too, so it isn't really about garbage collection."

### Java vs Python GC

> "Python is primarily reference-counted with a generational cycle detector on top, so releases are deterministic but cycles need a separate pass and the heap is never compacted. Java is purely tracing, so cycles are free and cost scales with the live set rather than the garbage — but you get no deterministic destruction. The practical upshot in Java is that allocating is cheap and *holding on* to objects is what costs you."

### Virtual threads

> "Virtual threads scale concurrency for blocking I/O; they don't add parallelism, so CPU-bound work still needs fork-join or a fixed pool sized to cores."

### Thread-safe booking — the canonical LLD concurrency answer

```java
// WRONG: check-then-act. Two threads can both see the seat as available.
if (seat.isAvailable()) { seat.book(user); }

// RIGHT: atomic, no lock needed.
private final ConcurrentHashMap<SeatId, UserId> booked = new ConcurrentHashMap<>();

boolean book(SeatId seat, UserId user) {
    return booked.putIfAbsent(seat, user) == null;   // exactly one winner, guaranteed
}
```

**Reach for the lock-free version first, and the lock only when the semantics demand it** (e.g. a hold-then-confirm flow needs a per-show `ReentrantLock` with `tryLock(timeout)`).

Then say the trade-off:

> "A lock on the whole `Show` is simpler but serialises all bookings for that show. Per-seat locks scale better but risk deadlock if I ever need two — so I'd acquire them in a consistent order by seat ID."

---

# APPENDICES

## Appendix A — Machine used for all measurements

```
CPU     Intel(R) Xeon(R) @ 2.10 GHz, 1 vCPU
L1d     48 KiB      L1i  32 KiB
L2      2 MiB       L3   260 MiB
OS      Ubuntu 24.04
gcc     13.3.0
Java    OpenJDK 21.0.10  (defaults to Serial GC here — 1 core)
Python  3.12.3
```

**Caveat carried through the notes:** single-core, so all parallelism results are floor values. The allocation and locality figures are unaffected.

---

## Appendix B — Verification commands

```bash
# C / C++
g++ -O2 -S x.cpp -o -                                    # READ THE ASSEMBLY. Always.
perf stat -e cache-misses,dTLB-load-misses,page-faults ./a.out
perf c2c record ./a.out                                  # false sharing
MALLOC_ARENA_MAX=1 ./a.out                               # observe arena contention

# Java
java -XX:-DoEscapeAnalysis -XX:-UseTLAB Bench.java       # isolate the two layers
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintEliminateAllocations
java -Xlog:gc                                            # minor/major GC, before->after(total)
java -Djdk.tracePinnedThreads=full                       # virtual-thread pinning
java -Djdk.traceVirtualThreadLocals
# use JMH for anything you would publish

# Go
go build -gcflags='-m -m' ./...       # "moved to heap:", "escapes to heap"
go test -bench=. -benchmem            # B/op and allocs/op
GODEBUG=gctrace=1 ./prog

# Python
PYTHONMALLOC=malloc python3 ...       # bypass pymalloc
python3 -c "import gc; print(gc.get_threshold(), gc.get_count())"
import tracemalloc, sys               # sys.getsizeof for per-object size
```

---

## Appendix C — Glossary

| Term | Meaning |
|---|---|
| **Cache line** | Fixed 64-byte block; the smallest unit the CPU transfers from memory |
| **Spatial locality** | Whether the next thing you need is near the last thing you fetched |
| **Pointer chasing** | Following references to reach data; defeats the prefetcher; dependent loads serialise |
| **Prefetcher** | Hardware that predicts and pre-loads future addresses when the stride is regular |
| **TLB** | Cache of virtual→physical address translations; a miss triggers a page walk |
| **Boxing** | Wrapping a primitive in an object so it can go in a collection; turns packed data into scattered pointers |
| **Live set** | Objects still reachable at GC time. **Java GC cost scales with this, not with garbage** |
| **TLAB** | Thread-Local Allocation Buffer — a thread's private slice of Eden, making allocation a pointer bump |
| **Escape analysis** | JIT/compiler proof that an object never leaves its scope. In Java → scalar replacement; in Go → a safety proof deciding stack vs heap |
| **Scalar replacement** | Dismantling a non-escaping object into register-held fields, removing the allocation |
| **Write barrier** | Instrumentation on every reference store so the GC can track cross-generation references |
| **Card table** | Bitmap of "dirty" old-gen regions, so minor GC needn't scan the whole old generation |
| **Arena** (glibc/pymalloc) | Large OS-obtained region subdivided by the allocator |
| **tcache / mcache / TLAB** | The same idea in three runtimes: a **per-thread, lock-free allocation fast path** |
| **Carrier thread** | The platform thread currently executing a virtual thread |
| **Mount / unmount** | Attaching or detaching a virtual thread from its carrier |
| **Pinning** | A virtual thread that cannot unmount (inside `synchronized`, or a native call) |
| **Work stealing** | An idle Fork/Join thread taking tasks from another thread's queue |
| **Concurrency** | How many tasks are *in flight* |
| **Parallelism** | How many tasks *execute simultaneously* |

---

## Appendix D — Corrected folklore

Things widely repeated that are wrong, with the correction.

| Claim | Reality |
|---|---|
| "Heap allocation is slow." | ~8× vs stack in C, ~20× in Java **with optimisations disabled**. With defaults on, ~4 ns in Java. Never contort a design to avoid `new`. |
| "The stack is fast because it's special fast memory." | It's the same DRAM. It's fast because **LIFO discipline** shrinks the allocator to one register and keeps the working set in L1 and a couple of TLB entries. |
| "GC cost comes from allocating a lot." | GC cost scales with the **live set**. A program allocating furiously where everything dies young can have near-zero GC cost — that's the entire premise of generational collection. |
| "Heap vs stack is the performance question." | The ~50× factor is **losing spatial locality**, which applies equally to `malloc`'d C with no GC. Layout is the lever; heap-vs-stack is a proxy for it. |
| "CPython arenas are 256 KiB, pools 4 KiB, 64 size classes." | Stale (pre-3.10). Current 64-bit: **1 MiB arenas, 16 KiB pools, 16 B alignment, 32 size classes.** |
| "Python 3.14 has an incremental GC." | Shipped, then **reverted in 3.14 and 3.15** after production memory-pressure reports. Back to the 3.13 generational GC. |
| "ZGC has a non-generational mode." | **Removed in JDK 24 (JEP 490).** Generational is now the only mode. |
| "The GIL makes Python thread-safe." | It serialises bytecode. **Check-then-act still races.** Use a `Lock` or `dict.setdefault()`. |
| "Virtual threads make everything faster." | Only blocking I/O. **No benefit for CPU-bound work** — carrier threads are capped at core count. |
| "Pool your threads, so pool your virtual threads." | **Don't.** Use a `Semaphore` on the constrained resource instead. |
| "`Integer` and `int` are basically the same." | `Integer[]` is an array of pointers to scattered 16-byte objects; `int[]` is contiguous. Oracle's own docs call the difference out. |

---

## Appendix E — Primary sources

- **dev.java** — Virtual Threads (Cay Horstmann); Parallelizing Streams; The Collections Framework
- **JVM Specification §2.5** — Run-Time Data Areas
- **JEP 425 / 436** — virtual threads (Java 19 / 20); final in Java 21
- **JEP 450 / 519 / 534** — compact object headers (JDK 24 → 25 → proposed default)
- **JEP 490** — removal of non-generational ZGC (JDK 24)
- **Go FAQ** — "How do I know whether a variable is allocated on the heap or the stack?"
- **Go runtime source** — `malloc.go`, `sizeclasses.go`, `stack.go`
- **CPython source** — `Include/internal/pycore_obmalloc.h` (arena/pool/alignment constants)
- **glibc malloc internals** — arenas, tcache, bins, mmap threshold

*Read primary sources for anything in these notes you intend to state confidently. Most secondary write-ups on pymalloc constants and ZGC modes are out of date.*

---

*End of notes.*