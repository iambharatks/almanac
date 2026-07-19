# 07 · Caching & Redis

> ⭐ **The one idea:** Caching strategies differ on two axes — **who handles a read miss** (app vs cache layer) and **what happens on write** (invalidate / sync-write / async-write / skip). Derive any strategy from those two questions rather than memorising five names.

---

## 1. What Redis is

An **in-memory key-value store** — no query engine, no schema, no "connect to a database" ceremony the way Postgres has. Just `GET`/`SET`/`INCR`/`EXPIRE` on keys. Fast because it's RAM, not disk.

```python
redis_client = redis.from_url(settings.redis_url, decode_responses=True)
await redis_client.set("url:abc123", "https://example.com", ex=3600)
await redis_client.get("url:abc123")
```

`ex=3600` = TTL in seconds — Redis expires the key automatically.

## 2. Cache-aside (what was built)

**Read:** check cache → hit? return. Miss? read DB → **write into cache** → return.
**Write:** write DB → **invalidate (delete)** the cache key. Next read repopulates it.

```python
cached = await redis_client.get(f"url:{short_code}")
if cached:
    return cached                              # hit
url = await db.execute(select(Url).where(...)) # miss → DB
await redis_client.set(f"url:{short_code}", url.original_url, ex=3600)
return url.original_url
```
On update/delete: `await redis_client.delete(f"url:{short_code}")`.

**Pros:** simple, resilient (cache dies → app still works via DB), only caches what's actually used.
**Cons:** first request for any item is a cold miss; brief staleness window between write and invalidation.

## 3. All five strategies — the two axes

| Strategy | Read path | Write path | Best for |
|---|---|---|---|
| **Cache-aside** (lazy) | app checks cache, loads DB on miss | write DB → **invalidate** cache | general read-heavy (**used here**) |
| **Read-through** | *cache layer* loads DB on miss (transparent) | pair with a write strategy | abstracting caching out of app code |
| **Write-through** | fast (always cached) | write cache **+** DB **synchronously** | consistency, read-soon-after-write |
| **Write-behind** (write-back) | fast (always cached) | write cache now, DB **async/batched** later | write-heavy + latency-sensitive (metrics/analytics) |
| **Write-around** | cache-aside on read | write **DB only**, skip cache | write-heavy, rarely re-read soon |

**The two axes, restated:**
- **Read:** does the *app* handle a miss (cache-aside) or the *cache layer* (read-through)?
- **Write:** invalidate / sync-write-both / write-cache-now-DB-later / DB-only?

**Trade-off tension (always the same):** speed vs consistency vs memory efficiency vs durability.
- Write-through → always consistent, but slower writes (two stores).
- Write-behind → fastest writes, batching — but **data-loss risk** if the cache dies before flush.
- Write-around → keeps the cache full of *read* data, but a just-written item is a guaranteed first-read miss.

> 💡 **Why cache-aside was right here:** redirects are read-heavy, updates rare → wanted resilience + lazy loading + simple invalidation. Didn't need write-through's stronger consistency. (Click analytics — write-heavy, rarely re-read — would suit write-behind or write-around instead.)

## 4. Eviction vs expiry — don't conflate

| | Trigger | Mechanism |
|---|---|---|
| **Expiry (TTL)** | time elapses | `EXPIRE` / `ex=` — key removed regardless of memory pressure |
| **Eviction** | memory is **full** | Redis picks a key to remove per `maxmemory-policy` |

**Eviction policies:** **LRU** (Least Recently Used — most common default) · **LFU** (Least Frequently Used) · **FIFO/random** (simpler, rarer). Configured via `maxmemory-policy` (`allkeys-lru`, `volatile-lru`, `allkeys-lfu`, …).

## 5. Rate limiting

**Fixed-window counter**, backed entirely by Redis so it's correct across every worker/process:

```python
key = f"ratelimit:user:{user.id}"
current = await redis_client.incr(key)          # atomic — no race even under concurrency
if current == 1:
    await redis_client.expire(key, WINDOW_SECONDS)
if current > settings.rate_limit:
    raise HTTPException(429, "Rate limit exceeded")
```

- **`INCR` is atomic** — Redis is single-threaded, commands execute one at a time, so concurrent increments from different workers can't lose updates (no locking needed).
- ⚠️ **`INCR` + `EXPIRE` are two separate commands** — a crash between them leaves a key with no TTL (never resets). Bulletproof version: a **Lua script** or pipeline making both atomic together.
- Implemented as a dependency chaining `Depends(get_current_user)` — auth *and* rate limiting from one `Depends(rate_limit)`.

**Why this must live in Redis, not a Python `dict`:** load balancers distribute **per request**, not per user — a single user's sequential requests routinely land on different workers. An in-process counter would allow `limit × N workers` total requests. The counter and its TTL living **in Redis** (not in any one process) is what makes the limit globally correct. *(Ties to the Alex Xu stateless-app-server principle — see doc 03 for the full shared-state-vs-per-process breakdown and the pool/client distinction.)*

## 6. Why Redis has no password by default

Redis assumes it runs on a **trusted, private network** — never exposed publicly — so auth was historically treated as unneeded overhead for something optimized for raw speed. Fine when genuinely network-isolated (as in this compose setup); a well-known **real-world attack vector** when accidentally exposed. Production: `requirepass` / ACLs (Redis 6+), or a managed service's built-in auth token (e.g. ElastiCache AUTH).

---

## 🎤 Interview answers

**"Walk me through the caching strategies you know."**
> They split on two questions: who handles a cache miss on read — the application or the cache layer — and what happens on write. Cache-aside is app-managed with invalidation on write, which is what I used for URL redirects since they're read-heavy with rare updates. Write-through keeps the cache always consistent by writing both stores synchronously, at the cost of slower writes. Write-behind writes the cache immediately and flushes to the database asynchronously, which is fast but risks losing data if the cache fails before the flush. Write-around skips the cache on write entirely, useful when written data isn't re-read soon.

**"How would you implement a distributed rate limiter?"**
> Store the counter in Redis, keyed per user, using `INCR` — which is atomic because Redis is single-threaded, so concurrent requests from different app instances can't race each other. Set a TTL on first increment to define the window. The key part is that the state lives in Redis, not in any one process, so it's correct no matter how many app instances or workers are running, since a load balancer spreads one user's requests across all of them.

---

## ✅ Gotcha checklist

- [ ] Cache-aside: read checks cache → DB → cache; write → DB → invalidate
- [ ] `INCR` is atomic; `INCR`+`EXPIRE` together are not
- [ ] Eviction (memory pressure) ≠ expiry (TTL)
- [ ] Rate-limit state must live in Redis, never in a Python variable, to be correct across workers
- [ ] Redis has no auth by default — fine only if genuinely network-isolated
- [ ] Match the caching strategy to the read/write pattern, not habit
