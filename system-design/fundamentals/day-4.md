# Day 4 — Storage Engines: How Databases Actually Work

**Sources:** DDIA Ch 3 "Storage and Retrieval" (pp. 91–132) · ARC: "Why is an SSD fast?" (p. 47), "Why is Redis so fast?" (p. 76)

**Time budget:** ~2.5 hrs — revision (15 min), Part A: OLTP engines (60 min), Part B: other indexes & in-memory (30 min), Part C: OLAP & column storage (40 min), ARC + self-test (25 min)

> **Note:** generated ahead of schedule at your request. Day 3's revision below recaps the *curriculum* for Day 3 (DDIA Ch 2); once you study Day 3, ask me to refresh this section with anything from your doubts.

---

## Revision — Day 3 in 10 minutes (Data Models & Query Languages)

- **Relational vs document:** relational handles many-to-one/many-to-many via joins; document DBs win on locality and schema flexibility but push joins into app code. Document = good for self-contained tree data; relational = good for interconnected data.
- **Schema-on-read vs schema-on-write:** document DBs don't enforce structure on write; relational schemas enforce it upfront — analogous to dynamic vs static type checking.
- **NoSQL drivers:** scalability beyond a single machine, preference for open source, specialized queries, restrictive relational schemas.
- **Object-relational impedance mismatch:** the awkward translation layer between OO code and tables — ORMs reduce but don't remove it.
- **Declarative queries (SQL)** specify *what*, not *how* — enabling the optimizer to choose the execution plan (today you learn what those plans run against).
- **Graph models:** property graphs (Cypher), triple-stores (SPARQL) — right choice when anything can relate to anything.

*Today flips perspective: Chapter 2 was the developer's view (what format do I give the DB); Chapter 3 is the database's view (how does it store and find data).*

---

# PART A — OLTP Storage Engines: Log-Structured vs Page-Oriented

Why care as an app developer? You won't build a storage engine, but you must *select and tune* one — and that requires a rough model of what it does under the hood. The chapter's big divide: **log-structured** engines (append-only) vs **update-in-place / page-oriented** engines (B-trees).

## A1. The world's simplest database

Two bash functions:

```bash
db_set () { echo "$1,$2" >> database; }
db_get () { grep "^$1," database | sed -e "s/^$1,//" | tail -n 1; }
```

`db_set` appends `key,value` to a file — old values are never overwritten, so `db_get` takes the *last* occurrence. Key insights:

- **Writes are fast** — appending to a file is the simplest possible write. Many real databases internally use exactly this: a **log** = an append-only sequence of records (not necessarily human-readable; "log" here ≠ application log).
- **Reads are terrible** — O(n) scan of the whole file per lookup.

The fix for reads is an **index**: additional metadata on the side acting as a signpost. **The fundamental tradeoff of storage systems: well-chosen indexes speed up reads, but every index slows down writes** (the index must be updated on every write). That's why databases don't index everything by default — you choose indexes from knowledge of query patterns.

## A2. Hash indexes (Bitcask)

Simplest indexing strategy for key-value data: an **in-memory hash map from every key → byte offset** in the data file. On write, append to file and update the map; on read, look up offset, seek, read. This is what **Bitcask** (Riak's default engine) does.

- Requirement: **all keys fit in RAM** (values can live on disk — one seek each, often zero thanks to filesystem cache).
- Sweet spot: **many writes per key, few distinct keys** (e.g., key = video URL, value = play count).

**Avoiding disk exhaustion:** break the log into **segments** of fixed size; when full, write to a new segment. Run **compaction** (keep only the most recent value per key) and **merge** several segments simultaneously in a background thread — segments are immutable, so reads continue on old segments until the merged one atomically replaces them. Each segment has its own hash map; lookups check the newest segment's map first, then older ones.

**Real-implementation details (classic interview trivia):**

- **File format:** binary (length-prefixed strings) beats CSV.
- **Deletion:** append a **tombstone** record; merging discards previous values for that key.
- **Crash recovery:** in-memory maps are lost on restart; rebuilding by scanning is slow, so Bitcask snapshots each segment's hash map to disk.
- **Partial writes:** checksums detect and ignore corrupted log fragments.
- **Concurrency:** one writer thread (sequential appends); immutable segments allow many concurrent readers.

**Why append-only beats update-in-place:** (1) sequential writes ≫ random writes, on spinning disks and to some extent SSDs; (2) concurrency and crash recovery are far simpler with immutable files (no half-old-half-new spliced values); (3) merging prevents fragmentation.

**Hash index limitations:** (1) the hash table must fit in memory — on-disk hash maps perform badly (random I/O, expensive growth, collision handling); (2) **range queries are impossible** — you can't scan `kitty00000`–`kitty99999` without looking up every key individually.

## A3. SSTables and LSM-trees

One change fixes both limitations: require each segment's key-value pairs to be **sorted by key** → **Sorted String Table (SSTable)**, with each key appearing once per segment (compaction guarantees this). Three advantages:

1. **Merging is simple and efficient even for files bigger than memory** — mergesort-style: read segments side by side, copy the lowest key to output. Same key in multiple segments? Keep the value from the most recent segment.
2. **The in-memory index can be sparse** — to find `handiwork`, knowing the offsets of `handbag` and `handsome` is enough: jump to `handbag` and scan. One index key per few kilobytes suffices.
3. **Compression** — since reads scan a range anyway, group records into blocks and compress each block; index entries point to block starts. Saves disk space *and* I/O bandwidth.

**But writes arrive unsorted — how do you write sorted files?** Keep the sorted structure *in memory*:

- Writes go to an in-memory balanced tree (red-black/AVL) — the **memtable**.
- Memtable exceeds a threshold (a few MB) → write it out as an SSTable file (already sorted); it becomes the newest segment. Writes continue to a fresh memtable meanwhile.
- Reads check: memtable → newest segment → next-older → …
- Background merging/compaction discards overwritten and deleted values.

**Crash problem:** memtable contents die with a crash. Fix: also append every write immediately to an unsorted **log on disk**, used only to restore the memtable; discard it whenever the memtable is flushed.

This whole scheme is the **LSM-tree (Log-Structured Merge-Tree)**: a cascade of SSTables merged in the background. Used by **LevelDB, RocksDB, Cassandra, HBase** (inspired by Google's **Bigtable** paper, which coined *SSTable* and *memtable*). **Lucene** (full-text engine behind Elasticsearch/Solr) uses a similar approach for its term dictionary (term → postings list, in sorted merged files).

**Performance optimizations:**

- **Bloom filters:** looking up a *nonexistent* key is worst-case (check memtable + every segment). A Bloom filter — memory-efficient approximate set — can say "definitely not present," skipping the disk reads entirely.
- **Compaction strategies:** **size-tiered** (newer, smaller SSTables merged into older, larger ones — HBase) vs **leveled** (key range split into smaller SSTables, older data in separate levels; more incremental, less disk — LevelDB/RocksDB). Cassandra supports both.

Because data is sorted, **range queries work**; because disk writes are sequential, **write throughput is remarkably high** — even with datasets far bigger than memory.

## A4. B-trees

The most widely used index — introduced **1970**, "ubiquitous" within a decade, standard in almost all relational DBs and many non-relational ones.

Like SSTables, B-trees keep keys sorted (efficient lookups + range queries). But the philosophy differs completely: instead of variable-size sequential segments, B-trees use **fixed-size blocks/pages, traditionally 4 KB**, read/written one page at a time — matching how disks are physically arranged.

- Pages refer to each other by on-disk address (a disk pointer). One page is the **root**; each page contains keys and child references; each child covers a contiguous key range. Follow references down to a **leaf page**, which holds values inline or references to them.
- **Branching factor** = number of child references per page — typically **several hundred**.
- **Update:** find the leaf, change the value, **overwrite the page in place** (references stay valid).
- **Insert:** add to the right page; if full, **split into two half-full pages and update the parent** — this keeps the tree **balanced**: depth O(log n). Most databases fit in **3–4 levels** — a 4-level tree of 4 KB pages with branching factor 500 stores up to **256 TB**.

**Making B-trees reliable:**

- Overwriting a page = real hardware operation (move head, wait for platter; SSDs must erase-rewrite large blocks). A page split writes *multiple* pages — crash mid-way corrupts the index (e.g., orphan pages). Fix: **write-ahead log (WAL / redo log)** — every modification is appended there *before* being applied to pages; used for crash recovery.
- Concurrency: in-place updates need **latches** (lightweight locks) to keep threads from seeing inconsistent states. Log-structured engines are simpler here — merging happens in the background, segments swap atomically.

**B-tree optimizations:** copy-on-write instead of WAL (LMDB — also good for snapshot isolation, Day 11); key abbreviation in interior pages (higher branching factor, fewer levels — the "B+ tree" variant); attempting sequential leaf layout on disk; sibling pointers between leaves for in-order scans; fractal trees (log-structured borrowings).

## A5. B-trees vs LSM-trees (the tradeoff table to memorize)

Rule of thumb: **LSM = faster writes; B-tree = faster reads** (LSM reads must check memtable + several SSTables at various compaction stages). But benchmarks are workload-sensitive — test with *your* workload.

**LSM advantages:**

- **Lower write amplification** (often): a B-tree writes everything at least twice (WAL + page), and writes whole pages even for tiny changes (some engines write pages twice to survive power failure). Write amplification = one DB write → multiple disk writes over the DB's lifetime; it eats disk bandwidth (write-heavy apps bottleneck here) and wears out SSDs.
- **Higher sustained write throughput:** sequential compact SSTable writes vs random page overwrites — especially big on magnetic disks.
- **Better compression / smaller files:** B-trees fragment (split pages leave gaps); LSM rewrites SSTables periodically, removing fragmentation (lowest overhead with leveled compaction).
- (On SSDs the firmware already turns random writes into sequential ones internally, so the gap narrows — but compactness still wins I/O bandwidth.)

**LSM downsides:**

- **Compaction can interfere with ongoing reads/writes:** usually small impact on average, but **high-percentile response times can spike; B-trees are more predictable**.
- **Compaction can fail to keep up at high write throughput:** disk bandwidth is shared between initial writes (log + memtable flush) and compaction threads; if compaction lags, unmerged segments accumulate until disk fills, and reads slow down. Engines typically *don't throttle* incoming writes — you need explicit monitoring.
- **B-trees have each key in exactly one place** — LSM may hold copies in several segments. That makes B-trees attractive for **strong transactional semantics**: range locks attach directly to the tree (Day 11).

## A6. Connections

The KV-store design in **SDI Ch 6 (Day 10)** uses exactly this stack: SSTables + memtable + Bloom filters + compaction — Cassandra/DynamoDB style. Day 1's "cache considerations" also echo here: the memtable+WAL pattern is the same "memory fast / disk durable" compromise.

---

# PART B — Other Indexing Structures & In-Memory Databases

## B1. Secondary indexes

Everything so far = primary key → value (unique). **Secondary indexes** (`CREATE INDEX`) are crucial for joins; keys are **not unique**. Two solutions: make the value a **list of matching row IDs** (like a postings list), or make each key unique by **appending the row ID**. Both B-trees and LSM indexes work as secondary indexes.

## B2. Storing values: heap files, clustered and covering indexes

The index *value* is either the actual row or a reference to it:

- **Heap file:** rows stored in no particular order; indexes point to heap locations. Avoids duplicating data across multiple secondary indexes. Updates that don't grow the value can overwrite in place; larger values must move — then all indexes update, or a **forwarding pointer** is left behind.
- **Clustered index:** store the row *inside* the index — avoids the index→heap hop. MySQL InnoDB: the primary key is always clustered; **secondary indexes point to the primary key**, not a heap location. SQL Server: one clustered index per table.
- **Covering index** (index with included columns): middle ground — stores *some* columns in the index, so some queries are answered from the index alone ("the index covers the query").

Clustered/covering indexes speed reads but cost storage, write overhead, and extra transactional care (duplication must stay consistent).

## B3. Multi-column indexes

- **Concatenated index:** fields combined into one key in a defined order — like a phone book on (lastname, firstname). Works for prefix queries (lastname; lastname+firstname) — useless for firstname alone.
- **Multi-dimensional indexes:** for simultaneous constraints on several columns — the geospatial classic: `WHERE latitude BETWEEN … AND longitude BETWEEN …`. A standard B-tree/LSM index can filter on one dimension only. Options: map 2D → 1D with a **space-filling curve** + regular B-tree, or specialized **R-trees** (PostGIS on PostgreSQL's GiST). Not just geography: (red, green, blue) for color search, (date, temperature) for weather queries — used by HyperDex. *(Day 27 — geohash/quadtree — builds on this.)*

## B4. Full-text search and fuzzy indexes

Exact-match and range indexes can't handle *similar* keys (misspellings). Lucene searches within an **edit distance** (edit distance 1 = one letter added/removed/replaced). Its in-memory term-dictionary index is a **finite state automaton over the keys' characters (like a trie)**, transformable into a **Levenshtein automaton** for efficient fuzzy search. Beyond that lies document classification and ML/IR territory.

## B5. Keeping everything in memory

Disks are awkward but durable and cheap per GB. As RAM got cheaper, **in-memory databases** emerged for datasets that fit in memory (possibly across machines).

- Memcached: cache-only, loss on restart acceptable.
- Durability options for real in-memory DBs: battery-powered RAM, **append-only change log on disk**, periodic snapshots, replication to other machines. Disk is used only for durability; reads never touch it. Restart = reload from disk/network replica.
- Examples: VoltDB, MemSQL, Oracle TimesTen (relational); RAMCloud (KV, durable); **Redis and Couchbase offer *weak* durability by writing to disk asynchronously**.
- **The counterintuitive point (exam favorite):** the speed advantage is NOT "no disk reads" — a disk-based engine with enough RAM never reads disk either (OS page cache). It's **avoiding the overhead of encoding in-memory structures into disk formats**.
- In-memory DBs can also offer data models that are hard to implement on disk — e.g., **Redis's priority queues and sets**.
- **Anti-caching:** evict LRU records from memory to disk, reload on access — like OS swap but at record granularity (indexes must still fit in memory). Future: non-volatile memory (NVM).

---

# PART C — OLTP vs OLAP & Column-Oriented Storage

## C1. Transaction processing vs analytics

*Transaction* = a group of low-latency reads/writes forming a logical unit (historical name — no money required, and not necessarily ACID; that's Day 11).

| Property | OLTP | OLAP |
|---|---|---|
| Read pattern | few records per query, fetched by key | aggregate over huge record counts |
| Write pattern | random-access, low-latency user input | bulk import (ETL) or event stream |
| Used by | end users via apps | internal analysts (business intelligence) |
| Data represents | latest state, current point in time | history of events over time |
| Size | GB–TB | TB–PB |
| Bottleneck | **disk seek time** | **disk bandwidth** |

Typical OLAP queries: total revenue per store in January; banana sales during a promotion vs usual; which baby-food brand co-sells with brand-X diapers.

## C2. Data warehousing

A large enterprise runs dozens of OLTP systems (website, point-of-sale, inventory, routing, suppliers, HR…) — each critical, low-latency, and closely guarded. Analysts can't run expensive scans on them. The **data warehouse** is a separate, read-only copy of all that data, optimized for analytics.

**ETL (Extract–Transform–Load):** extract from OLTP systems (periodic dump or continuous stream), transform into an analysis-friendly schema, clean, load. Warehouses are ubiquitous in large enterprises, rare in small ones (a small company's data fits in one SQL DB or a spreadsheet).

Both expose SQL, but internals diverge — most vendors focus on one workload. (SQL Server and SAP HANA do both, but increasingly as two engines behind one SQL front.) Commercial: Teradata, Vertica, SAP HANA, ParAccel (Amazon **Redshift** = hosted ParAccel). Open-source SQL-on-Hadoop: Hive, Spark SQL, Impala, Presto, Tajo, Drill (some based on Google's Dremel).

## C3. Star and snowflake schemas

Analytics data modeling is formulaic: the **star schema** (dimensional modeling).

- Center: **fact table** (e.g., `fact_sales`) — one row per *event* (a purchase, a page view). Facts are kept as individual events for later analysis flexibility → fact tables get huge (Apple/Walmart/eBay: tens of PB, mostly facts).
- Fact columns: **attributes** (price sold, supplier cost → margin) and **foreign keys to dimension tables** — the who/what/where/when/how/why. Example: `dim_product` (SKU, brand, category…), `dim_store`, `dim_date` (yes, dates are dimensions — lets you encode holidays), `dim_promotion`, `dim_customer`.
- Visualized, fact table sits in the middle with dimension rays — hence "star."
- **Snowflake schema:** dimensions further normalized into subdimensions (brand table, category table referenced from `dim_product`). More normalized, but analysts prefer stars for simplicity.
- Tables are **wide**: fact tables often 100+ columns; dimension tables too.

## C4. Column-oriented storage

The problem: trillions of rows × 100+ columns, but a typical query touches only **4–5 columns**. Row-oriented storage (all OLTP DBs; document DBs too) must load *entire rows* (100+ attributes), parse, filter — slow even with indexes on the filter columns.

**Idea: store all values of each *column* together, one file per column.** A query reads and parses only the columns it uses. Requirement: every column file holds rows **in the same order** — the kth entry of every column file belongs to row k (that's how rows are reassembled). Applies to non-relational data too — **Parquet** is columnar with a document model (from Dremel).

### Column compression

Column values are repetitive → compress well. Star technique: **bitmap encoding**. A column with n distinct values (usually n ≪ row count — billions of sales, 100k products) becomes **n bitmaps: one per distinct value, one bit per row** (1 = row has that value). Small n (country ≈ 200 values): store bitmaps raw. Larger n: bitmaps are sparse → **run-length encode** them — remarkably compact.

Bitmap operations answer warehouse queries directly:

- `WHERE product_sk IN (30, 68, 69)` → bitwise **OR** of three bitmaps.
- `WHERE product_sk = 31 AND store_sk = 3` → bitwise **AND** (works because all columns share row order).

**Caution:** Cassandra/HBase "column families" (from Bigtable) are NOT column-oriented — within a family, a row's columns are stored together; the model is mostly row-oriented.

### Memory bandwidth and vectorized processing

Beyond disk→memory bandwidth, analytical engines optimize memory→CPU-cache bandwidth: iterate tight loops over chunks of compressed column data that fit in **L1 cache**, avoid branch mispredictions, use **SIMD** instructions. Operators (AND/OR) work on compressed chunks directly — **vectorized processing**.

### Sort order in column storage

Rows can be stored insertion-ordered (append to each column file) — or sorted, SSTable-style, as an indexing mechanism. You can't sort columns independently (row alignment would break); sort **whole rows**, expressed column-wise. Admin picks sort keys from query patterns — e.g., `date_key` first (queries target recent ranges), `product_sk` second (group same product/day together).

Bonus: sorting boosts compression — the first sort key has long runs of repeated values → run-length encoding compresses a billion-row column to a few KB. Effect fades for second/third keys (more jumbled).

**Several sort orders (C-Store / Vertica):** data must be replicated anyway — store each replica **sorted differently** and use the version fitting the query. Like multiple secondary indexes in a row store, except: row stores keep the data in one place with pointers from indexes; column stores have no pointers, just columns of values.

### Writing to column storage

Sorted, compressed columns make in-place updates impossible — inserting a row mid-sort would rewrite all column files. Solution: **LSM-trees again** — writes go to an in-memory sorted store; when enough accumulate, they're merged with the on-disk column files and written in bulk (this is what Vertica does). Queries combine disk columns + recent memory writes; the optimizer hides it.

## C5. Aggregation: materialized views and data cubes

Warehouse queries repeat the same aggregates (COUNT/SUM/AVG/MIN/MAX) — why re-crunch raw data?

- **Materialized view:** a view whose results are actually written to disk (a virtual view is just query shorthand). Must be updated when underlying data changes → writes get expensive → rare in OLTP, sensible in read-heavy warehouses.
- **Data cube (OLAP cube):** special case — a grid of aggregates grouped by dimensions. 2D example: date × product, each cell = SUM(net_price) for that combination; summarize along any axis for one-dimension-reduced totals. Real facts have more dimensions (date/product/store/promotion/customer = 5D hypercube).
- Tradeoff: precomputed queries are extremely fast ("total sales per store yesterday" = read one row of totals), but **no flexibility** — can't ask about sales of items > $100 if price isn't a dimension. Warehouses keep raw data primary; cubes are a performance boost only.

---

# Chapter summary in four sentences

OLTP engines come in two schools: **log-structured** (append-only: Bitcask, SSTables, LSM-trees, LevelDB, Cassandra, HBase, Lucene) and **update-in-place** (fixed-size pages: B-trees, in all major relational DBs). Log-structured engines' key trick is turning **random writes into sequential writes**, buying higher write throughput. Analytics is a different world: queries scan millions of rows, seek time stops mattering, **disk bandwidth** dominates, indexes fade, and **compact encoding + column-oriented storage** wins. Armed with this, you can pick the right engine and understand its tuning knobs.

---

# ARC Supplements

**Why is an SSD fast? (p. 47):** SSD reads up to ~10× and writes up to ~20× faster than HDD. Flash-based: bits in cells of floating-gate transistors, purely electronic — **no moving mechanical parts** (vs HDD head-seek + platter rotation). Connects directly to today: seek time is the OLTP bottleneck; SSDs shrink it but don't remove the sequential-vs-random gap entirely — and SSD firmware internally uses **log-structured algorithms** itself (write amplification wears cells out — same term, same concern as LSM compaction).

**Why is Redis so fast? (p. 76):** three reasons: (1) **RAM-based** — RAM access ≥ ~1000× faster than random disk access; (2) **I/O multiplexing + single-threaded execution loop** — no locking/context-switch overhead; (3) **efficient low-level data structures**. Ties to B5: Redis persists asynchronously (weak durability) and its in-memory nature enables rich structures (sorted sets, priority queues) that are hard to build disk-first.

---

# Watch (revision — after reading, not before)

From the "Systems Design 2.0" playlist (Jordan has no life): **Database Indexes: What do they do?** (10:45) · **How do Hash Indexes work?** (13:35) · **How do B-Tree Indexes work?** (9:12) · **LSM Tree + SSTable Database Indexes** (15:35) · **Indexes Concluded** (7:02) · **Column Oriented Storage (with Parquet!)** (13:03) — ~70 min total; if short on time, prioritize the LSM and B-tree videos, which map directly to your Q&A notes above.

---

# Self-Test (do without looking)

1. Write the two bash functions of the "simplest database." Why are its writes fast and reads slow, and what's the general fix?
2. State the fundamental index tradeoff in one sentence.
3. Bitcask: what are its two key requirements/assumptions, and its ideal workload?
4. What is a tombstone and when is it honored?
5. Give the two limitations of hash indexes that SSTables fix, and the three advantages sorted segments bring.
6. Walk the full write path and read path of an LSM-tree, including crash recovery. Where do Bloom filters help, and with what query exactly?
7. Size-tiered vs leveled compaction — which engines use which?
8. A B-tree page is 4 KB with branching factor 500. How much data fits in 4 levels? Why does a B-tree need a WAL while an LSM segment file doesn't?
9. Give three LSM advantages and three LSM downsides vs B-trees. Define write amplification and explain why SSD owners care.
10. Clustered vs covering vs heap-file storage of values — one sentence each, with the InnoDB specifics.
11. Why can't a standard B-tree answer `lat BETWEEN x AND y AND lon BETWEEN a AND b` efficiently? Two remedies?
12. Why are in-memory databases fast (the counterintuitive answer)? What's anti-caching?
13. Reproduce the OLTP vs OLAP table from memory, including the bottleneck row.
14. Star vs snowflake schema; what's a fact table row, and why do date dimensions exist?
15. Explain bitmap encoding of a column and how `WHERE product_sk IN (30,68,69)` executes. Why do sorted columns compress so well, and how does Vertica exploit replicas?
16. How do column stores handle writes if sorted compressed files can't be updated in place?

---

# Doubts & Clarifications

## Interview Q&A — LSM internals (added by Bharat, 2026-07-04)

### 1. LSM-Tree Compaction

**Q: If LSM-Trees are append-only, why and how does compaction happen?**

- **The Problem:** Because data is never overwritten in place, updates and deletes (tombstones) are appended as new rows. Over time this causes **storage exhaustion** (duplicate keys) and **read amplification** (checking too many files to find the newest data).
- **The Solution (Compaction):** A background process that merges old SSTables together. It scans keys, keeps only the most recent value for duplicates, discards deleted tombstone records, and writes a fresh, clean SSTable to disk.
- *Cross-ref:* compaction itself is why **write amplification** exists (§A5) — one logical write is rewritten repeatedly across merge generations. Strategy choice (size-tiered vs leveled) tunes this tradeoff.

### 2. Secondary Indexes & the Write Penalty

**Q: If an SSTable is sorted by Name (primary key), how do we index by Age?**

- **The Mechanism:** The database creates a completely separate, parallel LSM-tree or B-tree for the secondary index. Key = `Age`, Value = pointer back to the primary key (`Name`).
- **The Read Path:** query the secondary index for `Age` → get the primary key → **table lookup** in the primary table for the full row. (This is exactly InnoDB's model — §B2: secondary indexes reference the primary key, and the primary key is a clustered index.)
- **⚠️ The Write Penalty:** updating a row with 4 indexed columns means the DB must write to **5 separate structures** on disk. This is why over-indexing destroys write-heavy system performance — the fundamental tradeoff from §A1: every index speeds reads and slows writes.

### 3. Sorting in an Append-Only Architecture

**Q: How can data be written to disk sequentially (append-only) but end up perfectly sorted?**

- **The sorting does NOT happen on disk.**
- **Step 1:** writes go into an in-memory **memtable** (a self-balancing tree, e.g., red-black/AVL), keeping data sorted in RAM instantly.
- **Step 2:** when the memtable fills (a few MB), it is **flushed sequentially** to disk. Because the tree was already sorted in RAM, the resulting SSTable is perfectly sorted — a single sequential write.

### 4. The Write-Ahead Log (WAL)

**Q: Does the WAL live in memory or on disk?**

- **Strictly on disk.** It is an unsorted, append-only log.
- **Purpose:** insurance. Writes hit the WAL first, then the memtable. On crash, volatile RAM is wiped; the DB replays the WAL to rebuild the memtable on reboot. Once a memtable is flushed to an SSTable, its WAL segment is discarded.
- *Note:* B-trees have their own WAL (redo log) for a different reason — surviving crashes mid-way through multi-page operations like splits (§A4).

### 🚨 Edge Case 1: Searching for Non-Existent Data

**Q: What happens if a backend queries a key that is NOT in the memtable and does NOT exist at all?**

- **The Danger:** the DB checks memtable → newest SSTable → … → oldest; a nonexistent key forces reading **every level on disk** just to confirm absence. Under high traffic (or a malicious flood of fake keys — compare Day 6's *cache miss attack*), this can take the DB down.
- **The Optimization (Bloom Filters):** a compact probabilistic set kept in RAM per SSTable. It answers *"absolutely not"* (skip the disk read entirely) or *"probably"* (proceed to read). False positives possible, false negatives impossible.

### 🚨 Edge Case 2: The Mid-Write Crash

**Q: What happens if the server crashes after writing to the WAL on disk, but before updating the memtable in RAM?**

- **Client perspective:** no "success" ACK was received → the backend assumes failure and surfaces a `500`.
- **DB perspective:** on reboot, the DB replays the WAL, finds the orphaned write, and inserts it into the recovered memtable.
- **The Result:** the DB has the data, but the client thinks the write failed — **"ghost data."** System design answer: make the API endpoint **idempotent**, so the client's automatic retry safely overwrites the recovered data instead of duplicating it. *(Idempotency returns as a core theme on Day 18 — exactly-once semantics — and Day 29 — avoiding double charges.)*

## Interview Q&A — B-tree internals (added by Bharat, 2026-07-04; accuracy-checked against DDIA)

### 1. B-Tree Fundamentals

The standard engine for relational databases (PostgreSQL, MySQL).

- **Architecture:** breaks the database into **fixed-size pages/blocks**, read/written exactly one page at a time. ✏️ *Accuracy note:* DDIA says **traditionally 4 KB** (sometimes bigger); in practice PostgreSQL uses **8 KB** and MySQL InnoDB uses **16 KB** — quote the right number for the DB you're discussing.
- **Branching factor:** the number of child pointers a page holds — typically **several hundred** (depends on space needed per key + page reference; ~500 is the standard worked example).
- **The Math:** ✏️ *corrected —* DDIA's example is a **4 KB** page with branching factor **500**: a tree just **4 levels deep stores up to ~256 TB** (not 500 TB). Depth is O(log n); most databases fit in **3–4 levels**, so finding any row costs at most ~4 page reads — and in practice fewer *physical* disk reads, because the root and upper levels are almost always cached in RAM.

### 2. The B+ Tree Upgrade

Modern DBs use B+ tree variants (DDIA notes the optimization is so common it's rarely even distinguished from "B-tree").

- **Strict separation:** internal nodes hold **only routing keys** (often abbreviated — enough to mark range boundaries), never row data. This maximizes the branching factor → fewer levels.
- **Data at the leaves:** all actual row data (or references to it) lives entirely in the **leaf nodes**.
- **The linked list:** each leaf has pointers to its **sibling pages** (doubly linked in InnoDB), forming a horizontal chain.
- **Range queries:** for `WHERE id BETWEEN 10 AND 100`, traverse down to `10`, then ignore the tree and **scan the sibling chain horizontally** until `100` — complex traversal becomes sequential reading. ✏️ *Caveat from §A4:* logically sequential leaves aren't guaranteed to be *physically* sequential on disk as the tree ages — LSM-trees keep sorted data physically contiguous more easily because merging rewrites large runs at once.

### 3. The Write Path & Crash Recovery

Unlike LSM-trees, B-trees **overwrite pages in place** on disk.

- **The write penalty (page splits):** `INSERT` into a full leaf → allocate a new page, split the data in half, update the parent's pointers. If the parent is full, *it* splits too — the split can **cascade up to the root** (which is how the tree gains a level). Splits add latency spikes and leave pages half-full (the fragmentation cost from §A5).
- **Crash recovery (WAL):** overwriting is dangerous — power failure mid-split leaves a corrupted index (e.g., orphan pages). So every modification is appended to the **write-ahead log (redo log) before** any page is modified; on reboot, the WAL restores consistency. ✏️ *Addition:* this means a B-tree writes each piece of data **at least twice** (WAL + page) — one root cause of its write amplification. Alternative design: **copy-on-write** (LMDB) skips the WAL by never overwriting pages at all.

### 4. Clustered vs Secondary Indexes

> **Definitions:**
> **Clustered index** — an index that *stores the actual row data in its leaf nodes*, so the table itself is physically organized by the index key. One per table at most (the data can only be sorted one way). Lookup by this key = one traversal, no extra hop.
> **Non-clustered (secondary) index** — a *separate* index structure whose leaves hold **no row data**, only a pointer to it (the primary key in InnoDB, a heap location in Postgres/SQL Server heaps). A table can have many; each lookup needs the extra hop to fetch the row.

The difference dictates whether a query takes one traversal or two.

- **Clustered (primary) index:** the B+ tree's **leaf nodes contain the actual row data** — the table IS the index, kept in primary-key order. Fetching by primary key is the fastest path: one traversal, done. ✏️ *Precision:* "dictates physical sorting of the hard drive" is approximately true — logical key order is maintained, but physical page placement drifts as pages split.
- **Secondary (non-clustered) index:** a separate B+ tree whose leaves hold **no row data**, only a pointer — in **InnoDB, the primary key value** (e.g., `email → ID: 100`).
- **The table-lookup penalty:** traverse the email B+ tree → get `ID 100` → jump to the clustered B+ tree → traverse again for the row. **Two traversals ≈ double the page reads.** (A **covering index** — §B2 — exists precisely to kill this second traversal for hot queries.)
- ✏️ *Accuracy note (PostgreSQL differs!):* Postgres has **no clustered index** — all rows live in a **heap file**, and *every* index (primary included) points to a heap location (TID). So the "secondary index penalty" in Postgres is index traversal + one heap fetch, for primary and secondary indexes alike. The clustered/secondary asymmetry described above is the **MySQL InnoDB** model. Know which one you're describing in an interview.

### Summary for System Design (verified)

- **MySQL (InnoDB):** the primary key is always a **clustered index** — row data is stored inside the tree's leaf pages, kept in key order.
- **SQL Server:** the primary key creates a clustered index **by default** — ✏️ nuance: it's configurable (you can declare a PK `NONCLUSTERED`, and you get one clustered index per table, which needn't be the PK).
- **PostgreSQL:** **no automatically maintained clustered indexes.** Primary keys are ordinary B-tree indexes pointing into an unordered **heap**; fetching a row requires the index → heap jump. ✏️ Two nuances: (1) the `CLUSTER` command physically reorders the heap by an index **once**, but Postgres does not maintain that order afterward; (2) the heap jump can be skipped by an **index-only scan** when the index covers all queried columns (and the visibility map allows it) — Postgres's version of the covering-index trick.

## Consolidated Revision Notes (cross-checked, added 2026-07-04)

*Bharat's consolidated summary, verified against DDIA. Duplicated Q&A items (compaction, secondary-index penalty, memtable sorting, WAL, Bloom filters, ghost data) are already covered above and not repeated; the B-tree "8KB / ~500 TB / 4 physical reads" figures were already corrected in the B-tree Q&A section (4 KB in DDIA's example, ~256 TB, upper levels cached in RAM). New and corrected material below.*

### Memtable data structures — expanded

DDIA names red-black/AVL trees; ✏️ *good addition:* **skip lists** are equally valid and what RocksDB/LevelDB actually use by default. Any structure that supports sorted insertion + in-order iteration works.

### Where each LSM component lives (verified — correct)

| Location | Components |
|---|---|
| **Main memory (RAM)** | Memtable (C0, mutable, sorted) · Bloom filters · sparse index (key → block offset per SSTable) |
| **Persistent disk (SSD/HDD)** | SSTables (immutable, sorted) · WAL (unsorted append-only, insurance for the memtable) |

Write flow: **WAL (disk) → memtable (RAM)** → ack. Flush: full memtable → new SSTable (sequential write), WAL segment discarded. Read flow: **memtable → newest SSTable → older SSTables**, with Bloom filters short-circuiting absent keys.

### Compaction strategies — WA/RA/SA tradeoff table (verified — correct, and a strong interview upgrade over DDIA's one-liner)

| Strategy | Optimizes for | How it works | Amplification profile |
|---|---|---|---|
| **Size-tiered (STCS)** — HBase, Cassandra default | **write throughput** | merge SSTables of similar size once a tier accumulates enough files; compactions are large and infrequent | **Low write amp · high read amp · high space amp** (duplicates linger; needs headroom for big merges) |
| **Leveled (LCS)** — LevelDB, RocksDB | **fast reads, low space** | fixed size-ratio levels; small incremental merges between levels; **within L1+ each level's SSTables have non-overlapping key ranges** (≤1 file to check per level) | **High write amp · low read amp · low space amp** |

✏️ *Precision:* "low/high" is relative — STCS still has write amplification (multi-generation rewrites), just less than LCS. The non-overlapping-ranges property is exactly *why* leveled reads are fast: a key can live in at most one SSTable per level.

### Storage engine families — corrected comparison table

| Feature | LSM-trees | B-trees |
|---|---|---|
| Write mechanism | sequential appends (memtable flush + compaction) | random in-place page overwrites (+ WAL) |
| Fundamental unit | sorted SSTable files (variable, MBs) | fixed-size page (4–16 KB) |
| Write speed | **very fast** (sequential I/O) | slower (random I/O, page splits, double-write) |
| Read speed | slower (multiple structures/levels; Bloom filters mitigate) | **fast & predictable** (guaranteed 3–4 level depth) |
| Latency profile | good average, **spiky high percentiles** (compaction interference) | **consistent** |
| Space use | ✏️ *corrected:* needs **temporary headroom during compaction**, but **at rest is usually SMALLER** — no fragmentation, better compression (esp. leveled) | ✏️ *corrected:* fragmented — split pages stay half-empty, so at-rest overhead is *higher*, not "efficient" |
| Transactional locking | harder — a key may exist in several segments | easier — **each key in exactly one place**; range locks attach to the tree |
| Best for | **write-heavy** workloads: logs, time-series, event streams, message data | **read-heavy / OLTP** random access, strong transactional semantics |
| Examples | LevelDB, RocksDB, Cassandra, HBase, Lucene | MySQL InnoDB, PostgreSQL, SQLite, SQL Server, Oracle |

✏️ *One removal:* the draft listed "analytics" under LSM's best-for. Analytics belongs to **column-oriented storage** (Part C) — a separate axis entirely. (Column stores *use* LSM-style writes internally, e.g., Vertica, but "LSM = analytics" is the wrong takeaway; OLAP wins come from columnar layout + compression, not from the LSM write path.)

### B-tree structure terms (verified, with DDIA vocabulary)

- **Order (M):** max children per node — DDIA calls this the **branching factor**; use that word in interviews.
- **Insertion:** insert at the leaf; on overflow the page **splits into two half-full pages** and the middle/boundary key is **promoted to the parent** — cascading upward is what grows tree height (a root split adds a level).
- Node size = disk block size to minimize I/O; each level ≈ one page read (upper levels usually cached).

## Read Skew / Non-Repeatable Read (added by Bharat, 2026-07-04 — verified)

> ⚠️ *Curriculum note:* this is **Day 11 material** (DDIA Ch 7, Transactions — pp. 255–264). Kept here because you noted it today; Day 11's document will treat it fully. It will appear in the cheat sheets covering both days.

**Definition:** an anomaly where a transaction reads the same data twice but gets different results, because another transaction modified and committed it in between. ✏️ *Precision:* the anomaly doesn't require re-reading the same row — reading *related* rows at different moments is enough (see the example).

**Mechanism (internal workflow):**

- **Snapshot context:** under **Read Committed** isolation (in MVCC databases like Postgres/InnoDB), each individual `SELECT` gets a fresh **statement-level snapshot** of committed state.
- **The conflict:** (1) `Tx1` reads Row A (state X) → (2) `Tx2` updates Row A→Y and Row B→Z, commits → (3) `Tx1` reads Row B (state Z).
- **The result:** `Tx1` sees a combination — old Row A + new Row B — **that never existed at any single point in time**.
- *DDIA's canonical example:* Alice has $1,000 across two accounts ($500 + $500). A transfer of $100 between them commits while she's viewing her balances; she sees one account *before* the transfer and the other *after* — total $900. Money appears to vanish.

**Key edge cases & fixes:**

- **Happens under:** Read Committed (it's *permitted* there by definition — RC only promises no dirty reads/writes).
- **When it's tolerable:** Alice reloads the page and sees consistent values — transient weirdness, often acceptable.
- **When it's NOT tolerable (DDIA's list):** long-running operations reading lots of data — **backups** (parts of the dump from different times → permanent inconsistency), **analytics queries**, and **integrity checks** all return nonsense under read skew.
- **Prevention:** **Snapshot Isolation / Repeatable Read** — the transaction reads from one snapshot taken **at transaction start** (not per statement). **Serializable** — strictest; transactions behave as if executed one after another.

**Implementation trick — MVCC (Multi-Version Concurrency Control):** each row carries hidden metadata recording which transaction created/deleted it (e.g., `created_by` / `deleted_by` txids in DDIA; InnoDB calls the mechanism a **"Read View"**). A snapshot = a rule for visibility: the transaction sees only rows committed **before it began** (plus its own writes), ignoring later changes and in-flight transactions — effectively freezing time for that session. Key property: **readers never block writers, writers never block readers.**

- *Connection to today:* MVCC is why **copy-on-write B-trees** (§A4, LMDB) are elegant — old page versions ARE old snapshots. And LSM-trees get MVCC almost for free: old values persist in older segments until compaction removes them.
- *Vocabulary warning for Day 11:* don't confuse **read skew** (stale reads across a snapshot boundary — fixed by snapshot isolation) with **write skew** (two transactions read the same data, then write to *different* rows based on stale premises — NOT fixed by snapshot isolation; needs serializability). The doctors-on-call example is the classic write-skew case.

## Non-Unique Secondary Indexes — Internal Mechanics (added by Bharat, 2026-07-04 — verified, with corrections)

> *This is the concrete implementation of §B2's abstract statement. DDIA gives exactly two solutions for non-unique secondary keys: (1) make each value a **list of matching row IDs** (postings list), or (2) make each key unique by **appending a row identifier**. Your notes describe how modern engines implement option 2.*

### The Problem

A secondary index key like `last_name = 'Smith'` may match 50,000 rows. Storing a variable-length list of pointers under one key inside a fixed-size page gets awkward (page overflow, expensive list maintenance). ✏️ *Precision:* B-trees don't *mathematically* require unique keys — duplicate-key B-trees exist (that's DDIA's option 1) — but duplicates complicate traversal, and above all make **targeted deletes expensive**. Uniqueness is an engineering choice, not a theorem.

### The Modern Internal Solution: Hidden Composite Keys

Modern engines transform the secondary index into a composite index by **appending the row's unique identifier to the key**:

- **MySQL (InnoDB):** appends the **primary key** — this is also *why* fat primary keys (UUIDs) bloat every secondary index in InnoDB.
- **PostgreSQL:** appends the physical heap pointer (**CTID/TID**). ✏️ *Version note:* this became true in **PostgreSQL 12** (heap TID treated as a tiebreaker key column); before that, Postgres B-trees simply tolerated duplicates.

### Inside the B-Tree Leaf Pages

Duplicates become distinct, unique, sorted tuples:

```
(Smith, 10) → row 10
(Smith, 45) → row 45
(Smith, 99) → row 99
```

A lookup for `Smith` is a **range scan** over the prefix `(Smith, *)` — same concatenated-index mechanics as §B3.

### The System Design Payoff

1. **Predictable search:** the tree stays strictly sorted; O(log N) holds.
2. **Fast deletes/updates:** deleting user 45 = search the unique key `(Smith, 45)`, purge one entry — no loading and scanning a pointer array.
3. **Deterministic index order:** ties are broken by the hidden ID, so scans return duplicates in a stable order. ✏️ *Caveat:* this does NOT make your API pagination automatically stable — SQL guarantees result order only for columns in your `ORDER BY`. For stable keyset pagination, still add the tiebreaker explicitly: `ORDER BY last_name, id`.

### ✏️ Bonus nuance (Postgres uses BOTH DDIA options)

**PostgreSQL 13** added B-tree **deduplication**: runs of equal keys can be physically stored once with a compressed **posting list of TIDs** — literally DDIA's option 1 — while logically still behaving like unique `(key, TID)` tuples. Space win for low-cardinality indexes. So the two "textbook alternatives" coexist in one engine: option 2 for semantics, option 1 for storage.

## Doubt: How exactly does column-oriented storage work — layout, indexing, bitmaps, vectorized processing? (asked 2026-07-04)

*Deep-dive answer expanding §C4; items marked ⊕ go beyond DDIA (real-system detail worth knowing in interviews).*

**1. Physical layout — position is the row ID.** One file per column; every file keeps values in the same row order. No row IDs are stored: a value's **position is its implicit row identifier** — row k = the kth entry of every column file. This is why columns can never be sorted independently (alignment is the only thing holding rows together), and why sorting happens whole-rows-at-a-time even though storage is per-column.

**2. Indexing without B-trees — three mechanisms:**

- **Sort order as index:** admin picks sort keys from query patterns; queries on the leading sort key scan only the relevant slice.
- ⊕ **Zone maps (block skipping):** column files are chunked into blocks with min/max metadata per block; `WHERE date > X` skips whole blocks whose max < X without reading them (Parquet row groups, ClickHouse granules, Redshift zone maps).
- **Bitmaps as indexes:** the bitwise operations below ARE the query execution — no tree traversal.

**3. Bitmap encoding, step by step.** Column with n distinct values (n ≪ rows): one bitmap per distinct value, one bit per row; bit k = 1 iff row k holds that value.

```
pos:  0 1 2 3 4        product_sk file: 30 30 68 30 69
30 →  1 1 0 1 0
68 →  0 0 1 0 0        IN (68,69):  00100 OR 00001 = 00101 → rows 2, 4
69 →  0 0 0 0 1        AND across columns works because row order is shared
```

Every row sets exactly one bit across the set — the bitmaps are an alternative *encoding* of the column, not extra data. Small n → raw bitmaps; large n → sparse bitmaps → **run-length encode** them (store run lengths, not bits). ⊕ Real stack (Parquet): **dictionary encoding** first (distinct values → small integers, bit-packed), then RLE on top.

**4. Sorting → compression multiplier.** Sorted-by column = long runs → RLE compresses a billion-row column to KBs. Strongest on the first sort key, fades by the third. **Vertica trick:** replicas (needed anyway) each store a *different sort order*; the optimizer picks the best-fitting one per query.

**5. Vectorized processing.** Two bottlenecks: disk→memory (solved by reading fewer columns + compression), then memory→CPU. Row engines process tuple-at-a-time (per-row function calls, branch mispredictions). Vectorized engines: take a **chunk of compressed column data sized for L1 cache**, run a tight branch-free loop, use **SIMD** (one instruction compares many values). Operators (AND/OR) run **directly on compressed data** — no decompress step. ⊕ **Late materialization:** carry only position bitmaps through the filters; assemble actual rows as late as possible so filtered-out rows are never materialized.

**6. Writes = LSM again.** Sorted compressed files can't take in-place inserts (one middle insert would rewrite all column files). Writes buffer in an in-memory sorted store; queries merge memory + disk transparently; background bulk merges rewrite column files (Vertica).

**Self-check:** 10M rows, 100k distinct values — why is the bitmap set still small? (a) Each bitmap is extremely sparse (≈100 ones in 10M bits) → RLE collapses it; (b) across all 100k bitmaps there are only 10M ones *total* (one per row) — the information content is the column itself, just rearranged.

---

# Tomorrow (Day 5)

Encoding & Evolution (DDIA Ch 4): JSON/XML vs binary formats, Thrift/Protocol Buffers/Avro, schema evolution & compatibility rules, and dataflow through databases, REST/RPC services, and message queues — plus ARC's SOAP vs REST vs GraphQL vs RPC and HTTP 1.0→3.0.