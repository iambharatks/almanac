# Anomaly Lab — Build Specification & Stepwise Prompts

*A tool that deliberately reproduces transaction anomalies against real database engines and reports which isolation levels prevent them. Spec first, tech-stack comparisons second, then copy-paste-ready stepwise prompts for building it (with an AI assistant or solo).*

---

## 1. Vision & Use Cases

**Core:** an interleaving executor that runs two (later N) scripted transactions against a target database with precise step-by-step control, observes outcomes, and classifies each anomaly as REPRODUCED / PREVENTED / PREVENTED-BY-ERROR (serialization failure + retry).

**Use cases, primary → stretch:**

1. **Learning/teaching:** live demonstration of dirty read, read skew, lost update, write skew, phantom — with actual SQL traces and row states per step. (Your Day 11 knowledge, executable.)
2. **Isolation matrix:** engine × isolation level × anomaly grid — the artifact for interviews and the README.
3. **Remediation gallery:** each anomaly paired with its fix scripts (`FOR UPDATE`, version column, unique constraint, exclusion constraint, SERIALIZABLE+retry) — proving the fix works, not just claiming it.
4. **Version-upgrade regression testing:** run matrix on engine vN+1, diff against vN — CI-friendly JSON output + nonzero exit on diff.
5. **"Compatible"-DB verification:** point at Aurora/Neon/CockroachDB/TiDB via connection string; discover where "Postgres-compatible" isolation semantics silently differ.
6. **Additional (stretch) implementations:**
   - **Contention benchmark:** SSI abort rates & retry throughput vs 2PL blocking latency under configurable contention (reproduces DDIA's performance claims empirically).
   - **Deadlock demonstrator:** opposite-order `FOR UPDATE` scripts; capture engine deadlock detection + victim choice.
   - **Locking-read semantics probe:** the InnoDB "two answers in one transaction" quirk vs Postgres RR serialization error (your Day 11 doubt, automated).
   - **MVCC bloat probe:** long-running transaction pinning versions; measure table bloat live (Postgres `pg_stat` / `pgstattuple`).
   - **Custom-engine target:** plug in your own PyLSM/MVCC engine (Project #2) once it exists.

## 2. What's Needed (requirements)

**Functional:**
- Script format (YAML) describing: setup SQL, N sessions, ordered steps (`session`, `sql` or `barrier`/`expect`), invariant query, cleanup.
- Executor: one DB connection per session; advances steps in exact scripted order; handles a step that *blocks* (lock wait) — must detect "blocked" vs "slow" (timeout + `pg_locks`/`information_schema` probe) and allow the schedule to continue with another session's step.
- Outcome classifier per run: invariant violated? / error raised (which)? / values observed at each read step.
- Per-engine dialect adapter: isolation-level syntax, error codes (PG `40001`/`40P01`, MySQL `1213`/`1205`), lock-probe queries.
- Runner: full matrix mode + single-script mode; JSON + HTML output; deterministic seeds.
**Non-functional:** each anomaly script < 30 lines of YAML; adding a new engine = one dialect file, zero core changes; full matrix run < 60 s locally; zero flakiness (barriers, not sleeps — sleeps are how these tools rot).

## 3. What to Focus On (the hard 20%)

1. **The interleaving executor is the whole project.** Blocking detection is the hardest part: when session B's step hangs on A's lock, that's often *the correct result* (prevention). Model step outcomes as `returned(rows) | blocked | error(code)` — all three are first-class results, not failures.
2. **Barriers, not sleeps.** Every step waits for explicit completion/blocked-detection of prior steps. `asyncio.Event` per step.
3. **Engine differences are the product.** PG RR aborting a lost update vs InnoDB silently allowing it is the most interesting row in your matrix — design the classifier so differences are loud.
4. **Reproducibility:** fresh schema per run (unique schema name), cleanup even on crash, containers pinned to exact versions.
5. Resist UI polish until the matrix is correct. JSON first, HTML later.

## 4. Tech Stack — comparisons & decisions

| Layer | Options | Pick | Why |
|---|---|---|---|
| Language/API | FastAPI vs Flask vs pure CLI | **FastAPI + CLI entry** (typer) | you want both: CLI for CI use case, HTTP for interactive demos; FastAPI's asyncio is the same event loop the executor needs |
| Concurrency | asyncio vs threads vs processes | **asyncio** | executor = many waiting connections, ~zero CPU; barrier control is cleanest with `asyncio.Event`; threads complicate blocked-step detection |
| PG driver | asyncpg vs psycopg3(async) | **psycopg3 async** | asyncpg is faster but exotic API; psycopg3 gives standard SQL parametrization + works sync too; speed is irrelevant here |
| MySQL driver | asyncmy vs aiomysql | **asyncmy** | maintained, faster; aiomysql is semi-dormant |
| Orchestration | docker-compose vs testcontainers-python | **testcontainers** | programmatic version pinning per run enables the vN vs vN+1 diff use case; compose as convenience fallback |
| Script format | YAML vs Python DSL | **YAML** (jinja2 for dialect substitution) | non-Python users can contribute scripts; the CI diff use case wants declarative inputs |
| Report | Jinja2 static HTML vs React vs Streamlit | **Jinja2 static HTML** | zero build chain, embeddable in README/CI artifacts; upgrade later only if needed |
| Testing | pytest + pytest-asyncio | — | plus one meta-test: every anomaly script must REPRODUCE at its weakest level (else the script is broken) |
| Packaging | pip package + Dockerfile | both | `pipx run anomaly-lab --target postgresql://...` is the demo that sells it |

## 5. Architecture (one page)

```
anomaly_lab/
├── scripts/            # YAML: anomalies/, remediations/, probes/
├── dialects/           # postgres.yaml, mysql.yaml, cockroach.yaml (syntax + error codes + lock probes)
├── core/
│   ├── executor.py     # sessions, barriers, blocked-detection, step outcomes
│   ├── classifier.py   # outcome → REPRODUCED / PREVENTED / PREVENTED_BY_ERROR
│   └── matrix.py       # engines × levels × scripts runner
├── targets.py          # testcontainers + external connection strings
├── report/             # json.py, html.py (jinja2 template)
├── cli.py              # typer: run, matrix, diff, serve
└── api.py              # FastAPI: POST /run, GET /matrix, GET /report
```

Script example (write skew):

```yaml
name: write-skew-doctors
setup: |
  CREATE TABLE doctors(name text primary key, on_call bool);
  INSERT INTO doctors VALUES ('alice', true), ('bob', true);
invariant: "SELECT count(*) >= 1 FROM doctors WHERE on_call"
sessions: [A, B]
steps:
  - {session: A, sql: "BEGIN"}
  - {session: B, sql: "BEGIN"}
  - {session: A, sql: "SELECT count(*) FROM doctors WHERE on_call", expect_rows: [[2]]}
  - {session: B, sql: "SELECT count(*) FROM doctors WHERE on_call", expect_rows: [[2]]}
  - {session: A, sql: "UPDATE doctors SET on_call=false WHERE name='alice'"}
  - {session: B, sql: "UPDATE doctors SET on_call=false WHERE name='bob'", may_block: true}
  - {session: A, sql: "COMMIT"}
  - {session: B, sql: "COMMIT", may_error: true}
```

## 6. Stepwise Build Prompts (copy-paste, one per phase)

> Use these sequentially with your AI coding assistant — or as your own milestone checklist. Each prompt assumes the previous phase's code exists. Keep each phase to one sitting; commit after each.

**Phase 0 — skeleton (1 evening):**
"Create a Python project `anomaly-lab` using uv/poetry: package layout as [paste §5 tree], typer CLI with a `run` stub, pytest + pytest-asyncio configured, testcontainers spinning up postgres:17 and mysql:8.4 in a session-scoped fixture, and a smoke test that connects to both and runs SELECT 1. Pin all versions. No business logic yet."

**Phase 1 — executor core (the heart; 1–2 weekends):**
"Implement `core/executor.py`: load a YAML script [paste schema from §5 example]. Open one async connection per session (psycopg3 async for Postgres). Execute steps strictly in listed order using asyncio Events as barriers. Each step returns an outcome: `returned(rows)`, `blocked`, or `error(sqlstate)`. Blocked detection: run the step as a task; if not done after 300 ms, query the dialect's lock-probe SQL over a separate admin connection to confirm it's waiting on a lock (Postgres: pg_locks join; else treat >2 s as blocked); a blocked step stays pending and the schedule proceeds; when a later step releases the lock, await the pending task and record its final outcome in the original step's slot. Support `may_block` and `may_error` annotations and `expect_rows` assertions. Write unit tests using two scripts: a trivially sequential one, and one where B blocks on A's row lock and unblocks after A commits."

**Phase 2 — classifier + first two anomalies (1 weekend):**
"Implement `core/classifier.py`: after a script run, evaluate the invariant query and combine with step outcomes into REPRODUCED (invariant violated or anomalous read observed), PREVENTED (blocked/serialized such that final state is correct, no error), PREVENTED_BY_ERROR (an expected serialization/deadlock error occurred; record sqlstate). Then write two anomaly scripts with per-step `observe` markers: read-skew-alice (two accounts, transfer between her reads; anomalous observation = totals ≠ 1000) and lost-update-counter (both read 42, both write 43; violation = final ≠ 44). Run both against Postgres at READ COMMITTED and REPEATABLE READ and assert: read skew REPRODUCED at RC, PREVENTED at RR; lost update REPRODUCED at RC, PREVENTED_BY_ERROR (40001) at RR."

**Phase 3 — full anomaly set + MySQL dialect (1 weekend):**
"Add dialect files postgres.yaml and mysql.yaml (SET TRANSACTION syntax, error codes: PG 40001 serialization/40P01 deadlock; MySQL 1213 deadlock/1205 lock wait timeout, plus lock-probe SQL) with jinja2 substitution in scripts. Add scripts: dirty-read-attempt, dirty-write-attempt, write-skew-doctors [paste §5], phantom-meeting-room (insert-overlap version). Add the meta-test: every anomaly script must be REPRODUCED on at least one engine/level, else fail. Produce the first full matrix run (2 engines × 3 levels × 6 anomalies) as JSON."

**Phase 4 — remediations + probes (1 weekend):**
"Add remediation scripts proving fixes: lost-update-for-update, lost-update-version-column, write-skew-for-update, phantom-unique-constraint, phantom-exclusion-constraint (PG only), write-skew-serializable-with-retry (retry loop on 40001, max 3). Add probe scripts: locking-read-semantics (InnoDB two-answers quirk vs PG RR 'could not serialize' — assert the engines differ) and deadlock-opposite-order. Matrix now has a 'remediation' section showing each fix flipping REPRODUCED → PREVENTED*."

**Phase 5 — outputs + CLI polish (1 weekend):**
"Implement report/json.py (stable ordering, schema-versioned) and report/html.py (single static page: matrix grid, click a cell → step-by-step trace with SQL, outcomes, observed rows). CLI: `anomaly-lab matrix --target <url>|--managed pg17,mysql84`, `anomaly-lab run <script> --level RR --trace`, `anomaly-lab diff old.json new.json` (exit 1 on semantic differences — the CI use case). FastAPI: POST /run, GET /matrix, GET /report serving the HTML."

**Phase 6 — the standout extras (pick per interest):**
- "Add `--target` support for arbitrary connection strings + a cockroach.yaml dialect; run the matrix against CockroachDB and document every difference vs Postgres in COMPATIBILITY.md."
- "Add contention benchmark mode: K concurrent transfer transactions over N accounts with zipfian skew, at RR-with-FOR-UPDATE vs SERIALIZABLE-with-retry; output p50/p99 latency + abort rate; plot with matplotlib."
- "Add version-diff CI recipe: GitHub Action running matrix on postgres:16 and postgres:17 and failing on diff."

## 7. Pitfalls (learn from them cheaply)

- **Sleeps instead of barriers** → flaky forever. Never `sleep` to "let the other transaction go first."
- **Connection pooling** — don't. Each session = one dedicated connection; pools reorder/reuse and destroy the experiment.
- **Autocommit surprises:** drivers differ on implicit BEGIN; set autocommit explicitly and issue BEGIN yourself.
- **MySQL lock wait timeout (default 50 s)** will stall runs — set `innodb_lock_wait_timeout=3` in the container.
- **Forgetting cleanup on error** → next run inherits locks/schema; always fresh schema name + teardown in `finally`.
- **Classifying "blocked" as failure** — it's usually the *prevention working*. Outcome model from §3 rule 1.

## 8. Portfolio Presentation

README order: (1) the matrix screenshot; (2) one sentence per use case; (3) a 20-line trace of write skew reproduced on InnoDB RR next to Postgres SSI aborting it; (4) COMPATIBILITY.md findings if any; (5) design notes. Blog post title that writes itself: *"I tested 6 transaction anomalies against 3 'Postgres-compatible' databases — here's what actually differs."*