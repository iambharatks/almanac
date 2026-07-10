# Day 11 — Transactions (DDIA Chapter 7)

**Sources:** DDIA Ch 7 "Transactions" (pp. 243–294) · ARC: "What are database isolation levels?" (pp. 4–5), "Optimistic locking" (p. 65)

**Time budget:** ~3 hrs (the densest chapter so far) — Part A: ACID & transaction scope (40 min), Part B: weak isolation levels (70 min), Part C: serializability (50 min), ARC + self-test (20 min)

> **Study mode: A (book-first — mandatory reading).** Read **DDIA Ch 7 in full, pp. 243–294**, BEFORE working through this document. This document is your consolidation pass and doubts log, not a substitute — Ch 7 is one of the four chapters (8, 11, 12, 13 in plan-days) where Kleppmann's narrative and figures (7-1, 7-6, 7-8, 7-10, 7-11) carry understanding no summary preserves. Videos come third, self-test last.

> **Study-order note:** generated out of sequence at your request (after Day 5, before Days 6–10). This is fine — Ch 7 only needs Ch 3's storage background, and you've already built the read-skew/MVCC foundation in your Day 4–5 notes, which this document extends. One heads-up: the short "replicated databases" part of Preventing Lost Updates references replication concepts (Day 8); it's flagged below and will click fully after Day 8.

---

## Revision — where you left off

Day 5 (last studied): backward compat = new code reads old data; forward compat = old code reads new data; rolling upgrades force both to coexist. Field tags (protobuf/Thrift) vs name-matching with writer's/reader's schemas (Avro). Services: backward-compat requests + forward-compat responses; RPC ≠ local call (six reasons — timeout ambiguity and retry duplication return TODAY as abort-retry pitfalls). Your accumulated transaction notes so far: read skew + MVCC (Day 4/5 doubts), ghost data + idempotency (Day 4). Today ties them all into the full framework.

---

# PART A — The Slippery Concept of a Transaction

## A1. Why transactions exist

Things go wrong constantly: DB/hardware failure mid-write, app crash mid-operation, network cuts, concurrent writes clobbering each other, partial reads, race conditions. A **transaction** groups several reads and writes into one logical unit that either **commits** entirely or **aborts** (rollback) entirely — so the application can safely retry and never worry about **partial failure**. Transactions are not a law of nature; they exist to **simplify the programming model** ("safety guarantees" — the DB handles classes of errors so your code can ignore them). Whether you need them is a real question — sometimes weakening or dropping them buys performance/availability.

## A2. ACID — precisely

Coined 1983 (Härder & Reuter). In practice "ACID compliant" is nearly a marketing term — implementations vary wildly (especially isolation). (Non-ACID systems get called **BASE** — Basically Available, Soft state, Eventual consistency — whose only sensible definition is "not ACID.")

- **Atomicity ≠ concurrency** (that's isolation's job). It means: if a transaction makes several writes and something fails partway, the DB **discards/undoes what it did so far** — nothing half-done is left behind. DDIA: "**abortability** would have been a better word." Implemented via the crash-recovery log (Day 4's WAL).
- **Consistency (the odd one out):** application-specific invariants ("credits = debits"). The DB can enforce some (uniqueness, foreign keys) but in general **consistency is the app's property, not the database's** — A, I, D are database properties that *help* you achieve C. Joe Hellerstein: the C was "tossed in to make the acronym work."
- **Isolation:** concurrent transactions can't step on each other — formally **serializability**: each transaction can pretend it's the only one running; the result equals *some* serial order. In practice serializable isolation is rare (performance) — **Oracle's "serializable" is actually snapshot isolation**.
- **Durability:** committed data survives crashes — WAL before ack, and/or replication. Perfect durability doesn't exist (disks die, SSDs corrupt on power loss, backups fail) — durability is always a **combination of techniques** reducing risk, never an absolute.

## A3. Single-object vs multi-object operations

- Even one object needs atomicity+isolation: a 20 KB JSON write interrupted halfway must not leave a spliced fragment. Engines give **single-object atomicity** (crash-recovery log) and isolation (per-object lock) universally. Increment and **compare-and-set** are single-object concurrency primitives — useful, but calling them "lightweight transactions" or "ACID" (Cassandra/Aerospike marketing) is a stretch.
- **Multi-object transactions** are what people usually mean — needed whenever writes must stay in sync: foreign-key correctness across rows, **denormalized data across documents** (document DBs' weakness!), **secondary indexes** (a record visible in one index but not another without isolation). Many distributed datastores dropped them (hard across partitions + antithetical to high availability in some views) — but nothing fundamentally prevents them.

## A4. Handling errors and aborts (interview-favorite pitfalls)

ACID philosophy: rather abandon a transaction than leave a guarantee violated — retry is the recovery path. But retrying aborted transactions isn't trivial:

1. Transaction actually **succeeded but the ack got lost** → retry duplicates the effect (needs app-level dedup/idempotency — your Day 4 "ghost data" note, formalized).
2. Abort due to **overload** → blind retries feed the fire; use retry limits + **exponential backoff**.
3. Only worth retrying **transient** errors (deadlock, network blip) — not permanent ones (constraint violation).
4. **Side effects outside the DB** (sending an email!) happen again on retry — needs 2PC or app care.
5. Client process dies mid-retry → data lost anyway.

Leaderless-replication datastores (Day 8 preview) work "best effort" instead — the DB won't undo partial work; error recovery is the app's job.

---

# PART B — Weak Isolation Levels

Serializable isolation costs performance, so most systems ship weaker levels — which is where real money has been lost (concurrency bugs at exchanges/bitcoin sites; "use an ACID database" doesn't save you, because *those are* the weak-isolation databases). The levels, weakest to strongest:

## B1. Read Committed

Two guarantees: **no dirty reads** (only see committed data) and **no dirty writes** (only overwrite committed data). *(A weaker level, read uncommitted, drops the first.)*

- **Why prevent dirty reads:** (1) partial-state visibility — new unread email visible but counter not yet updated → other transactions make wrong decisions; (2) you could see data that's later **rolled back** — never actually existed.
- **Why prevent dirty writes:** interleaved writes to multiple objects mix outcomes — car-sale example: listing says **Bob** bought it, invoice goes to **Alice**. (But note: read committed does NOT stop the counter race — that's a lost update, not a dirty write, because the second write comes *after* commit.)
- **Implementation:** dirty writes → **row-level write locks** held to commit/abort. Dirty reads → NOT read locks (one long writer would stall all readers); instead the DB **remembers the old committed value** and serves it to readers until commit — the 2-version seed of MVCC. (Only DB2 and SQL Server in `read_committed_snapshot=off` still use read locks.) Default level in Oracle, PostgreSQL, SQL Server, MemSQL…

## B2. Snapshot Isolation and Repeatable Read

*(You know the core from your Day 4/5 notes — here's the complete treatment.)*

- **The anomaly it fixes — read skew / nonrepeatable read:** Alice, $500+$500, transfer commits between her reads → sees $900. Acceptable-by-definition under read committed. Intolerable for **backups** (hours-long copy mixing eras → *permanent* inconsistency on restore) and **analytics/integrity checks** (nonsense results). *(Note "skew" is overloaded: hot-spot skew (Day 9) vs this timing anomaly.)*
- **The idea:** each transaction reads from a **consistent snapshot** = the DB as of transaction start. Supported by PostgreSQL, MySQL/InnoDB, Oracle, SQL Server.
- **Implementation — MVCC in full:** write locks for dirty-write prevention (writers can block writers), but **reads need no locks: readers never block writers, writers never block readers.** Each transaction gets a unique, always-increasing **txid**; every row version is tagged `created_by` (and `deleted_by` on delete — actual removal happens later by GC/vacuum; updates = delete + create). **Visibility rules:** ignore (1) writes by transactions that were in-progress when yours started, (2) writes by aborted transactions, (3) writes by later txids; everything else visible. I.e., a row is visible iff its creator committed before your start AND it isn't deleted by anyone who committed before your start.
- **Indexes under MVCC:** option 1 — index points to all versions, filter on read (PostgreSQL; optimization: skip version housekeeping if all versions fit one page). Option 2 — **append-only/copy-on-write B-trees** (CouchDB, Datomic, LMDB): each write creates a new tree root = a consistent snapshot by construction, no filtering — *this is your Day 4 copy-on-write connection made explicit.*
- **The naming disaster (know this cold):** the SQL standard's isolation levels (from System R, 1975) predate snapshot isolation, so databases shoehorn it in — **Oracle calls it "serializable"; PostgreSQL and MySQL call it "repeatable read"**; DB2 uses "repeatable read" to mean *serializability*. The standard's definitions are "ambiguous, imprecise, and not implementation-independent" — practically, **nobody knows what repeatable read really means**.

## B3. Preventing Lost Updates

The **read-modify-write** race: two clients read a value, both modify, both write — the later write **clobbers** the earlier update (counter increments, editing a JSON list field, two users editing a wiki page). Five solutions:

1. **Atomic write operations:** `UPDATE counters SET value = value + 1 WHERE key = 'foo'` — implemented by an exclusive lock on read ("**cursor stability**") or single-thread execution. Best when expressible; beware ORMs silently generating unsafe read-modify-write instead.
2. **Explicit locking — `SELECT ... FOR UPDATE`:** lock all rows the decision depends on; forces cycles to run sequentially. (Multiplayer-game example: several figures, rules to check — atomic ops don't fit.) Risk: forget a lock → race remains.
3. **Automatic lost-update detection:** let cycles run in parallel; abort the loser. Works naturally with snapshot isolation — **PostgreSQL repeatable read, Oracle serializable, SQL Server snapshot do detect it; MySQL/InnoDB repeatable read does NOT** (leading some to argue InnoDB doesn't truly provide snapshot isolation). Big win: no app code needed, can't be forgotten.
4. **Compare-and-set:** `UPDATE ... SET content = 'new' WHERE id = 1234 AND content = 'old'` — retry if no rows matched. ⚠️ Under MVCC the `WHERE` may read an old snapshot and still "succeed" — check your DB's semantics before relying on it.
5. **Replicated context (Day 8 preview 🔗):** multi-leader/leaderless DBs can't lock/CAS a single copy — they allow **conflicting siblings** and merge afterward (commutative atomic ops like Riak counters merge automatically); **last-write-wins (LWW) — the default in many DBs — is inherently lost-update-prone.**

## B4. Write Skew and Phantoms

**The doctors-on-call story:** constraint = ≥1 doctor on call. Alice and Bob both feel ill, both click "go off call" ~simultaneously. Under snapshot isolation both `SELECT count` checks return 2 → both proceed → both commit → **zero doctors on call.** Neither dirty write nor lost update — they updated *different rows*. Race exists only because they ran concurrently.

- **Characterization:** write skew = **generalization of lost update**: two transactions read the same objects, then update *some* of them — same object → dirty write/lost update; different objects → write skew.
- **Your options shrink:** atomic single-object ops don't apply (multiple objects); automatic detection needs true serializability (Postgres RR, InnoDB RR, Oracle serializable, SQL Server SI all fail to catch it); multi-object constraints are mostly unsupported (triggers/materialized views as hacks); fallback = **`SELECT ... FOR UPDATE` on the rows the decision depends on** — works for the doctors (the checked rows exist).
- **More write-skew examples:** meeting-room double booking (check overlap → insert), multiplayer-game position conflicts, **username claiming** (check-then-insert — fixable with a unique constraint!), **double-spending** (insert tentative item, check balance stays positive).
- **The phantom pattern (all examples share it):** (1) SELECT checks a requirement; (2) app decides; (3) write **changes the very precondition checked in (1)**. When the check looks for the *absence* of rows, `FOR UPDATE` has **nothing to lock** — the row that would conflict doesn't exist yet. A write in one transaction changing another transaction's search result = **phantom**. Snapshot isolation dodges phantoms in read-only queries but not in read-write cycles.
- **Materializing conflicts (last resort):** manufacture lockable rows — e.g., pre-create a `(room, 15-min slot)` table for six months, `FOR UPDATE` those rows before booking. It's a pure lock table, not data. Ugly (concurrency control leaking into the schema) — prefer serializability.

---

# PART C — Serializability

Weak levels are hard to reason about, inconsistently named/implemented, and race detection tooling is basically nonexistent. Researchers' answer since the 1970s: **just use serializable isolation** — the DB guarantees the outcome equals *some* one-at-a-time execution, preventing **all** race conditions. Three implementation families:

## C1. Actual Serial Execution

Remove concurrency entirely: **one transaction at a time, one thread**. Considered absurd for 30 years; feasible from ~2007 because (1) **RAM got cheap** — active dataset fits in memory, (2) OLTP transactions are **short, few reads/writes** (long analytics run outside the loop on a snapshot). Used by **VoltDB/H-Store, Redis, Datomic**. Can beat concurrent systems by shedding all locking overhead — but throughput ≤ **one CPU core**.

- **Stored procedures required:** interactive client/server transactions spend most time on **network round-trips**; a serial engine can't wait for a client between statements (nor for humans — that died long ago; a transaction never spans user think-time or multiple HTTP requests). So the whole transaction ships to the DB up front. Old stored-procedure gripes (vendor languages — PL/SQL, T-SQL; hard to version/debug/monitor; a bad proc hurts a shared DB badly) are addressed with general-purpose languages: **VoltDB: Java/Groovy; Datomic: Java/Clojure; Redis: Lua**. VoltDB also **replicates by running the same procedure on every replica → procedures must be deterministic** (special APIs for now()/random()).
- **Partitioning (Day 9 🔗):** to scale past one core, partition so each transaction touches **one partition** → one thread per partition/core, linear scaling. **Cross-partition transactions need lock-step coordination: ~1,000 writes/sec in VoltDB — orders of magnitude slower, not fixable by adding machines.** Key-value data partitions easily; multiple secondary indexes → lots of cross-partition traffic.
- **Viability constraints:** every transaction small + fast (one straggler stalls everything); active data in memory (or abort → async-fetch → restart = anti-caching); write throughput per core/partition manageable; cross-partition use rare.

## C2. Two-Phase Locking (2PL)

THE serializability algorithm for ~30 years. ⚠️ **2PL ≠ 2PC** (two-phase *commit*, Day 13).

- **The rule:** readers block writers AND writers block readers (contrast snapshot isolation's mantra). Locks per object in two modes: **shared** (many readers may hold together) and **exclusive** (writers; no coexistence). Read → shared; write → exclusive; read-then-write → **upgrade**. **Phase 1: acquire locks during execution; phase 2: release at commit/abort** — that's the "two phases," never mid-transaction.
- **Deadlocks** (A waits on B, B on A) become frequent; the DB detects and aborts a victim, app retries — wasted work.
- Used by: MySQL/InnoDB and SQL Server serializable level; DB2 repeatable read.
- **Why everybody avoids it — performance:** lock overhead plus, crucially, **lost concurrency**: anything that *might* race waits. Unbounded queues form behind slow transactions (no transaction duration limits in traditional DBs) → **unstable latency, terrible high percentiles** (Day 1's tail-latency lens); one slow lock-heavy transaction grinds the system.
- **Predicate locks — killing phantoms:** a lock on **all objects matching a search condition, including ones that don't exist yet.** Reader takes a shared predicate lock on its query condition; any writer whose old *or new* value matches someone's predicate must wait. 2PL + predicate locks ⇒ full serializability.
- **Index-range locks (next-key locking) — the practical version:** predicate matching is expensive, so approximate: **expand the predicate to a coarser, index-attached range** (room 123 at *any* time; or *all* rooms noon–1pm) — safe because the approximation is a superset. A conflicting insert touches the same index region, hits the shared lock, waits. Less precise (locks more than needed), far cheaper. No usable index → fall back to locking the whole table.

## C3. Serializable Snapshot Isolation (SSI)

The promising middle path: **full serializability at a small penalty over snapshot isolation.** New — 2008, Michael Cahill's PhD; ships as **PostgreSQL's serializable level (9.1+)** and (similar algorithm) FoundationDB.

- **Pessimistic vs optimistic:** 2PL = pessimistic (anything might go wrong → wait); serial execution = pessimism taken to the limit (one big lock, compensated by tiny transactions). SSI = **optimistic**: proceed without blocking; **at commit, check whether isolation was violated**; abort and retry if so. Old idea (debated since the '70s); loses badly under **high contention** (abort storms, worse near max throughput — mitigate with commutative atomic ops like counters), wins when contention is modest and there's spare capacity.
- **The unifying insight — decisions on an outdated premise:** every write-skew case is *query → decision → write*, where the commit-time truth may differ from the query-time premise. The DB can't know how the app used a query result, so it must detect **any change to the premise**. Two detection cases:
  1. **Stale MVCC reads:** txn 43's snapshot ignored txn 42's then-uncommitted write; if 42 commits before 43 does, 43's premise broke → **abort 43 at commit time.** Why wait? The read may never matter (read-only txn), and 42 might abort — aborting eagerly would kill snapshot isolation's long-read support.
  2. **Writes that affect prior reads:** like index-range locks **minus the blocking** — record in the index "txns 42, 43 read this entry"; a writer touching that data **notifies** the readers (a "tripwire," not a block). First committer wins; a notified transaction that commits later aborts.
- **Performance:** tracking granularity is a tradeoff (fine = precise but heavy bookkeeping; coarse = more false-positive aborts). Postgres uses theory-backed pruning of unnecessary aborts. vs 2PL: **no blocking → predictable latency, great for read-heavy** loads. vs serial execution: **not bound to one core** — FoundationDB distributes conflict detection across machines, cross-partition serializable transactions included. Requirement: keep read-write transactions **short** (long ones keep tripping conflicts).

---

# The Master Table — anomalies × isolation levels

| Anomaly | What happens | Read Committed | Snapshot Isolation | Serializable |
|---|---|---|---|---|
| Dirty read | see uncommitted data | ✅ prevented | ✅ | ✅ |
| Dirty write | overwrite uncommitted data | ✅ prevented | ✅ | ✅ |
| Read skew (non-repeatable read) | inconsistent point-in-time view | ❌ allowed | ✅ (the snapshot) | ✅ |
| Lost update | r-m-w clobber | ❌ | ⚠️ *some* engines auto-detect (PG yes, InnoDB no); else FOR UPDATE / atomic ops / CAS | ✅ |
| Write skew | disjoint writes on a shared stale premise | ❌ | ❌ | ✅ only |
| Phantom | write changes another txn's query result | ❌ | read-only: ✅ / read-write: ❌ | ✅ (predicate/index-range locks or SSI tripwires) |

Three roads to serializable: **serial execution** (in-memory, short txns, ≤1 core/partition — VoltDB/Redis) · **2PL** (pessimistic, blocking, ugly tails) · **SSI** (optimistic, commit-time validation — PostgreSQL).

---

# ARC Supplements

**Database isolation levels (pp. 4–5):** the four ANSI levels top-down — **Serializable** (concurrent txns as-if-sequential), **Repeatable Read** (data read during the txn stays the same as at txn start), **Read Committed** (only committed data visible), **Read Uncommitted** (dirty reads possible). Use it as a recall skeleton — but layer today's nuance on top: "repeatable read" in PG/MySQL actually = snapshot isolation, and the standard's definitions are famously ambiguous.

**Optimistic locking (p. 65):** application-level optimistic concurrency control — add a **`version` column**; read the version, write back `version + 1` with a validation check (`WHERE version = <read value>` — the DB rejects if someone got there first), retry on failure. Version number beats timestamp (server clocks drift — Day 12 foreshadowing). This is DDIA's **compare-and-set** dressed for app code, and SSI is the same optimism moved *inside* the engine. Great under low contention; retry storms under high contention — identical tradeoff to SSI.

---

# Watch (revision — after reading, not before)

From "Systems Design 2.0" (Jordan has no life), the full Day 11 set (~60 min): **Intro to ACID Transactions** (7:49) · **Read Committed Isolation** (9:31) · **Snapshot Isolation** (7:08) · **Write Skew and Phantom Writes** (6:38) · **Achieving ACID: Serial Execution** (6:05) · **Two Phase Locking** (10:31) · **Serializable Snapshot Isolation** (8:28) · **What's VoltDB?** (8:14). If pressed, prioritize Write Skew + SSI.

---

# Self-Test (do without looking)

1. Why does DDIA say "abortability" would be a better word than atomicity? What exactly does the A *not* cover?
2. Why doesn't the C belong in ACID? Give an example of a consistency property the DB can't enforce alone.
3. List four pitfalls of blindly retrying an aborted transaction.
4. Dirty write vs lost update: the car-sale race is which one, and why isn't the counter race a dirty write?
5. How does read committed prevent dirty reads *without* read locks? Which two databases still use read locks for it?
6. Walk through MVCC visibility: a row created by txn 40 (committed), deleted by txn 45 (in progress) — what does txn 43 see, and why?
7. State the two index strategies under MVCC and name a database for each.
8. Why does "repeatable read" mean nothing precise? Name what Oracle, PostgreSQL, and DB2 each call their levels.
9. Five ways to prevent lost updates — and which popular engine's repeatable read does NOT auto-detect them?
10. Doctors example: why does `SELECT FOR UPDATE` fix it, but NOT fix the meeting-room booking? What's the name of the underlying effect, and what's the last-resort workaround?
11. Recite the write-skew pattern in three steps. Classify: username claiming — write skew or not, and what's the cheap fix?
12. Serial execution: the two ~2007 developments that made it viable, why stored procedures are mandatory, VoltDB's determinism requirement, and the cross-partition number.
13. 2PL: what are the two phases? Why are its tail latencies bad? Predicate lock vs index-range lock in one sentence each.
14. SSI: the two detection mechanisms, why aborts wait until commit, and when SSI performs worse than 2PL.
15. Fill in the master table from memory (six anomalies × three levels).

---

# Doubts & Clarifications

## Doubt: How does snapshot isolation actually solve the isolation problem — working? (asked 2026-07-07)

**The problem restated.** Read committed re-decides visibility *per statement*, so a transaction reading related rows at different moments can straddle another's commit (read skew). Snapshot isolation's fix: **freeze the visibility decision once, at transaction start, for the whole transaction** — the DB appears frozen at one instant.

**The working, step by step:**

1. **Nothing is overwritten.** Every write creates a new row *version*. `UPDATE` = mark old version `deleted_by` + write new version `created_by`. Rows form version chains; GC (Postgres VACUUM) removes versions no open transaction can need.
2. **Every transaction gets an always-increasing txid**; versions are tagged with the writer's txid.
3. **A snapshot is metadata, not a copy:** my start point + the set of txids in progress at my start.
4. **Visibility check on every read** — a version is visible iff: its creator committed *before my start* (in-progress-at-start creators stay invisible *even after they commit*), it isn't from an aborted txn, its txid isn't later than mine, and any deletion of it wasn't committed before my start. Latest visible version in the chain wins.

**Worked example (Alice = txn 12, transfer = txn 13):** both accounts have version `500 (created_by 5, deleted_by 13)` and versions `400/600 (created_by 13)`. Txn 13 was in flight when 12 started → 13's versions fail the check → Alice reads 500 + 500 = **1000, consistent**. Under read committed she'd read 500+600=1100 or 400+500=900 depending on timing — never the truth. A txn 14 starting after 13's commit sees only 400/600.

**Three boundary facts:**

- **Snapshot governs reads only — writes still take row locks** (dirty writes prevented as usual; writer-writer conflicts still block). Hence: *readers never block writers, writers never block readers* — long analytics/backups run on a frozen snapshot at zero lock cost.
- **What it does NOT solve:** two transactions can read the same snapshot, decide, and write *disjoint* rows that jointly break an invariant — **write skew** (no row-lock conflict ever occurs). Lost updates: auto-detected in some engines (PG), not others (InnoDB). Full fix = SSI: same snapshots + commit-time premise-violation detection.
- **Cost:** version-chain storage + GC pressure; long-running transactions pin old versions (bloat) — why DBAs fear idle-in-transaction sessions.

**One-line answer:** *snapshot isolation gives every transaction a private, immutable point-in-time view built from row versions + txid visibility rules — reads become time travel, writes stay locked.*

## Write Skew & SSI — consolidated notes (added by Bharat, 2026-07-07 — corrected & completed)

### Definition (verified)

**Write skew** = two concurrent transactions read the same data, make independent decisions on it, and write to **different rows**, jointly violating a global constraint or business rule. DDIA framing: a *generalization of lost update* — same pattern, but the writes land on disjoint objects.

### Why snapshot isolation is blind to it

✏️ *One correction to the draft:* "Snapshot isolation successfully prevents lost updates" is **engine-dependent** — PostgreSQL repeatable read, Oracle serializable, and SQL Server snapshot **do** auto-detect lost updates (first-committer-wins); **MySQL/InnoDB repeatable read does NOT** (needs `FOR UPDATE` / atomic ops / version column). What's universally true: SI is **completely blind to write skew**, because detection (where it exists) triggers on write-write collision on the *same row* — and in write skew there is no such collision. Each transaction updates a separate record; every lock check passes; both commit.

### The two-case decision table (from chat discussion — memorize this split)

Txns 12 and 13 both read the value committed by txn 11, both write:

| They write… | Anomaly | Under plain SI | Fix |
|---|---|---|---|
| the **same row** | lost update | **Postgres RR: second committer aborts** (serialization error, retry). **InnoDB RR: silent clobber** | auto-detection, `FOR UPDATE`, atomic update, or version column |
| **different rows** (shared premise) | write skew | **both commit everywhere — no error, invariant silently breaks** | SSI / serializable; else `FOR UPDATE` on the premise rows; if the premise is *absence* of rows (phantom) → unique constraint or materialized conflicts |

### The classic case: doctors on call

- **Business rule:** ≥1 doctor with `on_call = true` per shift. **Initial state:** Alice and Bob both on call.
- Both feel ill; both transactions run `SELECT count(*) WHERE on_call = true` → both see **2** (same snapshot) → both pass the check → Alice sets *her* row off-call, Bob sets *his* → no row conflict → **both commit → 0 doctors on call.**
- **Manual fix here:** `SELECT ... FOR UPDATE` on the on-call rows works, because the premise rows *exist* and can be locked.
- **Where FOR UPDATE fails:** premises about *absent* rows (meeting-room overlap check, username free, sufficient balance) — nothing to lock; the conflicting row appears only after the check = **phantom**. Username case: rescued cheaply by a **unique constraint** (the DB serializes on the index entry itself — the second insert violates it and aborts). Room booking has no natural unique key → serializability or materialized conflicts.

### How SSI catches it

Both transactions run optimistically on their snapshots. SSI tracks (a) **stale MVCC reads** (a version I ignored got committed) and (b) **writes affecting prior reads** — index-entry tripwires recording "txn 12 and 13 read this data"; a write to it *notifies* rather than blocks. **First committer wins; the second, whose premise is now stale, aborts at commit** and retries. Full serializability at near-SI cost; the price is abort/retry churn under high contention and a requirement that read-write transactions stay short.

> Positioning: this is "the pinnacle of single-node concurrency control" in the sense that it closes the last anomaly class (premise-based races) that every weaker level leaks — but remember serial execution and 2PL solve it too, at different costs (§C1–C3).

## SSI internals — accuracy-checked notes (added by Bharat, 2026-07-07)

### Core philosophy (verified, one precision)

- **2PL (pessimistic):** readers block writers and writers block readers via shared/exclusive locks (+ index-range locks for phantoms); anything that *might* conflict waits → queues → terrible tail latency.
- **SSI (optimistic):** transactions execute unimpeded on their snapshots, assuming conflicts are rare; safety is **verified at commit time**, aborting the unlucky. ✏️ *Precision:* "all reads and writes execute unimpeded" is true of read-vs-write interaction, but **writer-writer conflicts on the same row still take ordinary write locks** (SSI sits on top of snapshot isolation, which keeps dirty-write prevention). SSI's read-tracking locks (Postgres calls them SIREAD locks) never block anyone.
- **Terminology upgrade (correct, beyond DDIA's wording):** what SSI hunts are **rw-antidependencies** — a write by one transaction invalidating a concurrent transaction's read. (In the Cahill/Fekete theory, an abort is required when *two consecutive* rw-antidependency edges form a dangerous structure; Postgres implements this refinement to cut false-positive aborts — DDIA alludes to it as "reducing unnecessary aborts.")

### The two detection mechanisms (✏️ corrected — the draft conflated them)

✏️ *Main fix:* the draft's "Heuristic A: the transaction reads a row version that hasn't been committed yet" is backwards — under MVCC a transaction **never reads uncommitted versions; it *ignores* them** by the visibility rules. That ignoring is exactly what's tracked:

- **Case 1 — stale MVCC reads (write happened BEFORE the read, still uncommitted):** txn A's snapshot *ignored* txn B's uncommitted write to a row A read. The DB notes "A ignored B's write." **At A's commit**, it checks: has B now committed? If yes, A's premise is stale → **abort A**. (No tripwire flag during execution — this one is a commit-time lookup. Why wait? B might abort; A might turn out read-only — either way the read was never actually stale, and eager aborts would destroy SI's long-read support.)
- **Case 2 — writes affecting prior reads (write happens AFTER the read):** when A queries, the engine records A's read on the touched **index entries/ranges** (table-level if no index) — like an index-range lock **that doesn't block**. When B later writes matching data, it finds the record and **notifies A** ("tripwire") that A's read is outdated — and vice versa. Tracking data is discarded once all concurrent transactions finish.

Draft's doctor mapping (verified, belongs to Case 2): Alice's `SELECT count(*)` attaches a read marker to the `shift_id = 1234` index range; Bob's `UPDATE` trips it; each flags the other.

### First committer wins (verified)

Resolution is decided by **commit order, not start order or read/write order**. Trace with Bob committing first:

1. Alice reads (2 on call) → marker on index range. Bob reads (2 on call) → marker likewise. Both proceed; both flagged via tripwires; **nobody blocks.**
2. **Bob commits → succeeds.** Although Alice's write "affects" him, she hasn't committed — nothing she did has taken effect, so his execution is still serializable (order: Alice-after-Bob… so far).
3. **Alice commits → aborted.** A conflicting write affecting her premise has *committed* since she read → serialization failure (Postgres: `could not serialize access due to read/write dependencies`).
4. **Alice retries.** Fresh snapshot now shows 1 doctor on call → her check fails → she stays on call. **The invariant holds — this retry-and-re-check is where the correctness actually lands.**

✏️ *One addition the draft's framing invites:* aborts are the *cost center* of SSI — the app **must** wrap transactions in retry loops, and long read-write transactions keep losing races (hence "keep them short"). Read-only transactions can often be spared entirely (provably safe on their snapshot), which is why detection defers to commit time.

## Doubt: Phantom vs write skew — a third doctor inserted concurrently (asked 2026-07-07)

**Scenario:** invariant ≥1 doctor active; Alice & Bob snapshot "2 active"; a third doctor's INSERT commits after their snapshots but before their commits. Both still read 2 though reality is 3. Phantom or write skew?

**Answer — they're different categories, and this instance is a benign phantom:**

1. **The insert IS a phantom** by definition: a write changing the result of another transaction's search query. SI hides it (that's the contract) — for read-only queries SI thereby "avoids phantoms": your repeated counts stay stable.
2. **Stale ≠ anomalous.** Reading 2-when-reality-is-3 is just the snapshot. Anomaly = decisions written on the stale premise **breaking an invariant**.
3. **Outcome check here:** 3 active − Alice − Bob = **1 active → invariant HOLDS.** The staleness *undercounted* the safety margin — conservative, harmless. So: phantom yes, write-skew failure no.
4. **The Alice↔Bob mutual skew still exists structurally** (each read what the other wrote) — SSI would still abort one of them regardless of the third doctor: it judges dependency structure (rw-antidependencies), not the luck of the final state.
5. **Relationship in one line:** *phantom = a mechanism by which a premise goes stale; write skew = the failure pattern of writing on a stale premise.* DDIA: "phantoms can lead to particularly tricky cases of write skew." Neither contains the other.
6. **Direction matters — the dangerous flip:** start with 3 active (A, B, C). **C goes off call and commits** after A's and B's snapshots. Both read "3 active" → both conclude "3 ≥ 2, safe to leave" → both commit → final **0 active, invariant destroyed.** Same phantom mechanism, but now the stale read *overcounts* the safety margin. Rule of thumb: a hidden concurrent change is harmful when it makes your snapshot look **safer than reality** with respect to the invariant.

## The Anomaly Ladder — memorable examples, phased fixes, and what real DBs do (asked 2026-07-07)

*Six anomalies, weakest to subtlest. Each: a scene you won't forget, a one-line mnemonic, fixes in escalation order (app trick → DB feature → isolation level), and real-world behavior.*

### 1. Dirty read — "reading someone's unsent draft"

**Scene:** you see a new email in the inbox but the unread counter still says 0 — the sender's transaction is half done. Worse: you read a value that later gets **rolled back** — you acted on data that never existed.
**Mnemonic:** *you read a lie.*
**Fix:** Read Committed or above — this is table stakes.
**Real world:** no mainstream DB permits dirty reads by default. The one place you meet them: SQL Server's `READ UNCOMMITTED` / `WITH (NOLOCK)` hint, still (ab)used for fast approximate reporting queries.

### 2. Dirty write — "two pens on the same wet ink"

**Scene:** the used-car race — Alice and Bob buy the same car "simultaneously"; listing table says **Bob** won, invoices table bills **Alice**. Two multi-row updates interleaved on *uncommitted* data.
**Mnemonic:** *the car goes to Bob, the bill goes to Alice.*
**Fix:** row-level write locks held to commit — every serious engine does this automatically at every level.
**Real world:** universally prevented; you never configure this.

### 3. Read skew / non-repeatable read — "the smeared photo"

**Scene:** Alice's $500 + $500; a transfer commits between her two balance reads; she sees **$900**. Like photographing a moving subject with a slow shutter — each pixel is real, the whole picture never existed.
**Mnemonic:** *panorama of a moving train = smear.*
**Fixes, phased:** (1) tolerate it for throwaway UI reads (reload fixes it); (2) wrap multi-read logic in one **snapshot transaction** (`REPEATABLE READ` in PG/MySQL); (3) for backups/analytics: tools do it for you — `pg_dump` runs in a snapshot; replicas serve consistent reads.
**Real world:** MVCC snapshots everywhere — PostgreSQL, InnoDB, Oracle, SQL Server (RCSI/snapshot), MongoDB/WiredTiger. This is a solved problem; you just have to *use* the transaction instead of firing independent queries.

### 4. Lost update — "last save wins, first save vanishes"

**Scene:** two editors open the same wiki page, both edit, both save — the first save silently disappears. Same shape: two `read counter, add 1, write back` → counter goes up by 1, not 2.
**Mnemonic:** *two people photocopy the form, fill it in, and the second photocopy overwrites the first.*
**Fixes, phased (this ladder IS the interview answer):**
1. **Atomic operation** when expressible: `UPDATE counters SET value = value + 1` — no read-modify-write cycle exists at all. (Redis `INCR`, Mongo `$inc` — same idea.)
2. **Pessimistic:** `SELECT ... FOR UPDATE` — lock the rows your edit depends on.
3. **Optimistic (version column):** `UPDATE page SET content=?, version=version+1 WHERE id=? AND version=?` → 0 rows updated = someone beat you → reload & retry. This is Hibernate/JPA `@Version`, DynamoDB condition expressions, HTTP `ETag`/`If-Match` — the most common app-level pattern in the wild.
4. **Engine auto-detection:** PostgreSQL RR / Oracle serializable / SQL Server snapshot abort the second writer (first-committer-wins). **InnoDB RR does NOT** — never rely on it in MySQL.
**Real world:** overwhelmingly #1 and #3. Atomic updates for counters/balances; version columns for entity edits; `FOR UPDATE` for short critical sections (inventory decrement).

### 5. Write skew — "two guards each leave because the other is staying"

**Scene:** doctors on call — both check "2 on call, safe", both leave, ward unattended. Two night guards at a museum, each phones in sick after confirming the other is on the roster — writes hit *different rows*, no lock ever collides, invariant dies.
**Mnemonic:** *nobody touched the same row, yet the rule is dead.*
**Fixes, phased:**
1. **`SELECT ... FOR UPDATE` on the premise rows** (works when the rows exist — doctors: lock all on-call rows for the shift before deciding).
2. **Single-row invariant redesign:** keep an `on_call_count` per shift and decrement atomically with a `CHECK (on_call_count >= 1)` — converts write skew into a lost-update/constraint problem the DB *can* see. Schema redesign as concurrency control — underrated, very real-world.
3. **True serializable isolation** (PG SSI, CockroachDB) — the only *automatic* cure.
**Real world:** most shops run RC/RR and handle write skew case-by-case with #1/#2, often not realizing they're doing it. Teams on PostgreSQL increasingly flip invariant-critical transactions to `SERIALIZABLE` + retry loop; CockroachDB made that the default posture.

### 6. Phantom — "stabbed by the row that wasn't there"

**Scene:** meeting-room booking — you check noon–1pm is free, insert your booking; someone else's conflicting booking *appears between your check and your commit*. Username claiming, double-spend — same shape: **the premise is the absence of rows, and `FOR UPDATE` can't lock nothing.**
**Mnemonic:** *you checked the room was empty; the ghost materialized after you looked.*
**Fixes, phased:**
1. **Unique constraint** whenever the invariant is "at most one X": usernames, one-booking-per-slot-key, idempotency keys. The index entry is the lock target you were missing. Cheapest, bulletproof.
2. **PostgreSQL exclusion constraint** — the real-world booking fix: `EXCLUDE USING gist (room_id WITH =, during WITH &&)` rejects overlapping ranges at the engine level. (This is materializing the conflict *inside* the index, elegantly.)
3. **InnoDB gap / next-key locks:** under RR, range scans lock the *gaps between* index entries, blocking phantom inserts — MySQL's partial answer, and the source of its infamous surprise deadlocks.
4. **Materialize conflicts** (pre-created slot rows to `FOR UPDATE`) — last resort per DDIA.
5. **Serializable** (SSI tripwires on index ranges / 2PL predicate- or index-range locks).
**Real world:** #1 and #2 carry most production systems; booking systems on Postgres genuinely use exclusion constraints; high-integrity systems use serializable.

### What real engines run today (verified July 2026)

| Engine | Default level | "Serializable" means | Notes you'll use in interviews |
|---|---|---|---|
| **PostgreSQL** | Read Committed | **true SSI** (9.1+) | RR = snapshot isolation with lost-update detection; SSI needs retry loops |
| **MySQL InnoDB** | **Repeatable Read** | 2PL (locking) | RR = SI *without* lost-update detection + gap locks; many shops lower to RC to reduce deadlocks |
| **Oracle** | Read Committed | ⚠️ **actually snapshot isolation** — write skew possible at "SERIALIZABLE" | the canonical naming trap |
| **SQL Server** | Read Committed (locking) | 2PL with range locks | RCSI/Snapshot optional (Azure SQL defaults RCSI **on**) |
| **CockroachDB** | **Serializable** (only distributed SQL DB defaulting there) | SSI-style optimistic | Read Committed added in 23.2 as opt-in |
| **Spanner** | Serializable + external consistency | TrueTime + 2PL | strongest guarantee in production use |
| **MongoDB** | snapshot (WiredTiger) for multi-doc txns | — | plus `$inc`-style atomic single-doc ops |
| **DynamoDB** | — (item-level) | — | OCC: condition expressions = CAS; TransactWriteItems |
| **SQLite** | Serializable | single-writer | serial execution by architecture! |

### The practitioner's playbook (what actually happens in production)

1. Run the engine default (RC or InnoDB-RR).
2. Push invariants into the **schema**: unique constraints, exclusion constraints, foreign keys, CHECKs — the DB catches races your code can't.
3. Use **atomic updates** for counters/balances; **version columns** (optimistic locking) for entity edits; **`FOR UPDATE`** for short check-then-act sections.
4. Wrap every transaction in a **retry-on-serialization-failure loop** with backoff, and make endpoints **idempotent** (ghost-data lesson).
5. Escalate *specific transactions* to `SERIALIZABLE` where an invariant spans rows and can't be schema-encoded — not the whole application.
6. Only reach for materialized conflicts when nothing else fits.

## Doubt: "Phantom read" vs "phantom write" — same or different? (asked 2026-07-07)

**One event, two vantage points, one sloppy term.** The canonical concept is the **phantom**: *a write in one transaction changes the result of another transaction's search query* (DDIA's definition).

- **Phantom read** (standard term, ANSI SQL anomaly): the reader's view — the same predicate query run twice in one transaction returns different rows (rows appeared/disappeared). Observable at read committed; **never observable under snapshot isolation** (repeated queries are stable).
- **"Phantom write"** (colloquial, not standard — e.g., video titles): the writer's view — the insert/delete/update that matches someone else's predicate. It's an ordinary write; there's no separate anomaly by this name.
- **Under SI the phantom goes underground:** queries are stable but *stale*; write on that premise and it resurfaces as **phantom-driven write skew**. Hence DDIA's careful scoping: SI "avoids phantoms in *read-only* queries."
- **Precision on "premise changed by another transaction":** that's the general mechanism. If the change is to a row you *read* (value modified) → plain stale premise, lockable with `FOR UPDATE`. If the change is rows **entering/leaving your predicate match** (insert/delete) → phantom — nastier, because there was nothing to lock. Fixes accordingly: unique/exclusion constraints, gap locks (InnoDB), predicate/index-range locks (2PL), SSI tripwires, or materialized conflicts.

## Doubt: Do real financial systems actually use serializable isolation? (researched 2026-07-08)

**Short answer:** the *stakes* justify it, but real systems do NOT run blanket serializable. They build a **layered portfolio** and use serializable guarantees selectively.

**Layer 1 — the risk is real.** Weak-isolation races have lost real money (DDIA's citations: bitcoin exchanges drained via concurrent-withdrawal double-spends = lost update / write skew on the balance check). A transfer is the textbook serializable use case.

**Layer 2 — traditional banks: pessimistic locking, not the isolation dial.** Cores run RC/RR + **`SELECT ... FOR UPDATE` on account rows in a fixed order** (lock lower account ID first → no deadlock), debit, credit, commit. Plus batch settlement windows and reconciliation. Correctness by disciplined locking + process.

**Layer 3 — modern fintech: make races structurally impossible (the architecture IS the concurrency control):**

- **Append-only, immutable double-entry ledger:** never `UPDATE balance` (overwrites are where lost updates live) — only INSERT balanced posting pairs; balances are **derived** from the log. An insert-only system has no lost updates by construction.
- **Sum-to-zero constraint** on postings (DB-enforced double-entry).
- **Idempotency keys** at the API = unique constraint (kills retry double-charges — the ghost-data fix, industrialized). Stripe/Adyen/Block layered pattern.
- **Optimistic version columns** where entities must be updated; **reconciliation jobs** as the audit backstop (detect what prevention missed).

**Layer 4 — serializable, used surgically or by platform choice:**

- Postgres shops: escalate only invariant-critical transactions to `SERIALIZABLE` (SSI) + retry loops.
- CockroachDB (default serializable) and Spanner (external consistency) chosen by fintechs to make it the platform default.
- **TigerBeetle** — purpose-built accounting DB with **strict serializability** (serializability + real-time order; Day 13 concept) implemented via **actual serial execution** (DDIA §C1: single-threaded state machine, in-memory accounts, VSR consensus replication). The chapter's "simplest" technique is the 2026 state of the art for ledgers.

**Exam-ready synthesis:** *financial correctness = invariants enforced by the strongest cheap mechanism available — schema constraints and append-only design first, ordered pessimistic locks on hot rows second, serializable isolation with retries for what remains — plus idempotency and reconciliation because prevention is never complete.*

**Resources (read in this order):**

1. [CockroachDB — SQL isolation levels explained](https://www.cockroachlabs.com/blog/sql-isolation-levels-explained/) — best single overview, includes the banking framing
2. [Formance — Double-entry accounting for engineers](https://www.formance.com/blog/engineering/double-entry-accounting-for-engineers-building-financial-products) — the ledger-first architecture
3. [Fintechly — Ledger system design principles](https://fintechly.com/infrastructure/infrastructure-ledger-system-design/) — idempotency, auditability, reconciliation
4. [CodeToDeploy — Solving the double spend](https://medium.com/codetodeploy/solving-the-double-spend-system-design-patterns-for-bulletproof-fintech-ee5d73f33415) — Stripe/Adyen/Block layered patterns
5. [TigerBeetle safety docs](https://docs.tigerbeetle.com/concepts/safety/) + [Jepsen analysis of TigerBeetle](https://jepsen.io/analyses/tigerbeetle-0.16.11) — strict serializability via serial execution, independently tested
6. [Serializable Snapshot Isolation in PostgreSQL (paper)](https://arxiv.org/pdf/1208.4179) — how SSI actually ships
7. [PlanetScale — Pitfalls of isolation levels in distributed databases](https://planetscale.com/blog/pitfalls-of-isolation-levels-in-distributed-databases) — why blanket serializable is avoided
8. [PostgreSQL docs — Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html) — the authoritative reference for what each level guarantees

## Doubt: Lost update vs dirty write (asked 2026-07-08)

Both = two writers on the same object. The difference is **when write #2 lands relative to writer #1's commit**:

- **Dirty write** — write #2 overwrites a value from a transaction that **hasn't committed yet** (wet ink). Car-sale example: listing says Bob, invoice says Alice. **Prevented automatically by every serious DB at every level** (row write locks held to commit). Never your job.
- **Lost update** — both **read committed data**, both compute, write #2 lands **after commit #1** — perfectly "clean," but it ignores writer #1's change because it was computed from a stale read. Counter: both read 42 → both write 43. **Read committed does NOT prevent it** — your job: atomic update / `FOR UPDATE` / version column / engine detection (PG RR yes, InnoDB RR no).

```
Dirty write:  W1 ── W2 overwrites ── (T1 uncommitted)      → DB always blocks
Lost update:  R1,R2 ── W1, COMMIT1 ── W2 clobbers, COMMIT2 → legal! you must stop it
```

**One-liner:** *dirty write = overwriting uncommitted data (the DB's problem); lost update = overwriting committed data you never read (your problem).* DDIA's exact contrast: the counter race is not a dirty write because the second write follows the first commit.

## Doubt: How does `SELECT ... FOR UPDATE` actually work? (asked 2026-07-08)

**What it does:** a plain MVCC `SELECT` takes no locks (snapshot read). Adding `FOR UPDATE` makes the SELECT acquire the **same exclusive row lock an UPDATE would take, on every returned row, at read time** — "I'm deciding based on these rows; freeze them."

**Mechanics (doctors example):**

```sql
BEGIN;
SELECT * FROM doctors WHERE on_call = true AND shift_id = 1234
FOR UPDATE;        -- ① locks all returned rows; blocks here if another txn holds any
-- ② app logic decides on the locked, guaranteed-current rows
UPDATE doctors SET on_call = false WHERE name = 'Alice' AND shift_id = 1234;  -- ③
COMMIT;            -- ④ locks release ONLY here (surgical 2PL phase 2)
```

Concurrent Bob blocks at his ①, resumes after Alice's commit, **re-reads current rows** (sees 1 on call) → his check fails. The check-then-act gap is closed because the lock happens at *read* time, before the decision — that's the entire point vs relying on UPDATE's own locks.

**Variants:**

- `FOR SHARE` (`LOCK IN SHARE MODE`, old MySQL) — shared lock: others may read-lock, none may modify. Use when the rows must stay stable but you won't write them.
- `FOR UPDATE NOWAIT` — error immediately instead of blocking (latency-sensitive paths).
- `FOR UPDATE SKIP LOCKED` — skip rows others hold; **the SQL job-queue pattern** (workers each claim different rows, no contention). Heavily used in production.

**Three gotchas:**

1. **Can't lock absent rows** — phantoms sail past (booking insert). Fix: unique/exclusion constraint, or serializable.
2. **Deadlocks** if transactions lock the same rows in different orders. Fix: fixed lock ordering (e.g., lower account ID first — the banking transfer pattern).
3. **It's a discipline, not a guarantee** — one code path that reads the same rows without the clause still races. (Exactly why SSI/serializable exists: correctness that doesn't depend on every developer remembering.)

## Doubt: "Locking reads read current versions, not snapshot versions" — what does that mean? (asked 2026-07-08)

**Two kinds of reads coexist in one transaction:**

- **Consistent read** (plain `SELECT`): reads your **snapshot** — possibly stale, lock-free, time travel.
- **Locking read** (`FOR UPDATE`/`FOR SHARE`, plus the implicit reads inside `UPDATE`/`DELETE`): waits for concurrent writers, then reads the **newest committed version — the current tip of the version chain — bypassing your snapshot.**

**Why it must be so:** a lock protects the row *as it exists now*. If the lock were granted but the query returned the snapshot's past version, you'd hold an exclusive lock on the present while deciding from the past — Bob would count 2 doctors while provably only 1 is on call, and write skew would walk straight through the lock. **The lock and the data must refer to the same version, or the lock is theater.**

**Bob's timeline (InnoDB RR):** snapshot shows Alice on call → `FOR UPDATE` blocks → Alice commits → lock granted → locking read evaluates *current* rows → sees Alice off → count = 1 → check fails → saved. Quirk: a plain `SELECT` right after would show the old snapshot again — **two different answers in one transaction** (documented, intended InnoDB behavior).

**Engine split (name the engine, always):**

- **InnoDB (RC and RR):** locking reads silently return the latest committed data.
- **PostgreSQL RC:** waits, re-evaluates the `WHERE` on the current row (EvalPlanQual), proceeds on current data.
- **PostgreSQL RR:** refuses to mix past and present — if the locked row changed since your snapshot: `ERROR: could not serialize access due to concurrent update` → retry with a fresh snapshot. Same safety, stricter philosophy.

**One-liner:** *plain reads answer "what was true when I started"; locking reads answer "what is true right now — and it stays true until I commit."*

---

# Next

Recommended: return to the curriculum order — Day 6 (caching & CDN deep dive) → Day 7 (consolidation + web security) → Days 8–10 (replication, partitioning, KV store), then you'll re-meet today's material from the distributed side on Days 12–13 (clocks, linearizability, 2PC — where 2PL's evil twin 2PC finally appears).