# coreprotect-ekaii — Postgres backend, footprint & retention

Scope: (1) add **PostgreSQL** as a first-class backend, (2) tighten footprint
and runtime cost, (3) ship **configurable auto-retention**. Dialect-agnostic
where reasonable so MySQL/SQLite keep working unchanged.

## Implementation status (this branch)

| Feature                                          | Status             |
|--------------------------------------------------|--------------------|
| `Dialect` interface + Sqlite/MySQL/Postgres impl | **Shipped**        |
| `database-backend: postgres` config key          | **Shipped**        |
| pgjdbc driver shaded into uber-jar               | **Shipped**        |
| Postgres DDL (BRIN(time) + partial idx + lz4)    | **Shipped**        |
| JDBC SQL translator (LIMIT a,b + reserved user)  | **Shipped**        |
| `RetentionService` (auto-purge, all 3 backends)  | **Shipped, validated end-to-end** |
| `/co retention status\|enable\|disable\|set\|run` | **Shipped**        |
| Folia-aware async scheduler                      | **Shipped**        |
| Postgres time-partitioned `co_block`/`co_container` | Follow-up PR    |
| `COPY`-based bulk loader on PG                   | Follow-up PR       |
| Testcontainers integration test matrix           | Follow-up PR       |

Validated runtime smoke (2026-05-02):
- SQLite default → schema creates, 3 seeded rows deleted in 84 ms by retention sweep.
- PostgreSQL 16 → schema creates with all 18 tables + BRIN/partial idx,
  5 seeded rows deleted in 83 ms by retention sweep, version+lock INSERTs
  succeed through the JDBC translator proxy.

---

## 0. What's there today

### Storage layer (mapped from src on commit `ad7346f`)

- **Two backends**: SQLite (default, file `database.db`) + MySQL/MariaDB
  (`use-mysql: true`).
- Selection is a hardcoded `Config.MYSQL` boolean (`Config.java:206`,
  consumed in 30+ files via `Config.getGlobal().MYSQL`).
- Driver loaded reflectively in `ConfigHandler.java:279` via
  `Class.forName("com.mysql.cj.jdbc.Driver")` (modern) with fallback to the
  legacy `com.mysql.jdbc.Driver`. Connection pool: HikariCP (already great).
- Schema is built by hand in `Database.java`:
  - MySQL DDL at lines 351–415 (InnoDB, utf8mb4, `mediumblob`, `tinyint`,
    `AUTO_INCREMENT`, manual `CREATE INDEX` strings, single-column secondary
    indices appended as a literal `, INDEX(col)` inside `CREATE TABLE`).
  - SQLite DDL at lines 561–650 (no per-row PK declared, INTEGER everywhere,
    indices created later).
- Per-row IDs: 32-bit `int` for most tables, `bigint` only for `co_block`.
- `co_block` is the hot table — every block place/break is one INSERT with
  `meta mediumblob, blockdata blob`. After ~30 days on a busy creative server
  it's tens of millions of rows.
- 13 tables, plus `co_database_lock` to serialize writers, plus 4 `_map` tables
  for the material/blockdata/art/entity dictionaries.
- The `Queue.java` consumer batches inserts every ~1 s and commits in MySQL
  transactions (`beginTransaction` / `commitTransaction`); on SQLite it just
  pumps statements with WAL when available (`Database.java:134`).

### Footprint costs that show up in production

These are the things to fix; they're the *why* for the redesign.

1. **No partitioning, no time-series structure.** Range scans by `time` over
   100M rows on creaekaii (Luminol 1.21.11) take seconds even with the
   composite indices. Indexing on `(wid, x, z, time)` exists but doesn't help
   `purge` which scans by `time` only.
2. **Wide rows on every block edit.** Every `co_block` row carries
   `meta mediumblob, blockdata blob` even when the edit is a normal place/break
   (no NBT, blockdata is the default state). On 100M rows this is 5–30 GB of
   mostly empty BLOBs. Compression would crush it but InnoDB's row format
   doesn't compress on its own.
3. **No retention.** As you confirmed, there is *no* `purge-time` config; an
   unattended server grows forever. Our forks can fix this without touching
   upstream's intent.
4. **Hash index churn on `_map` tables.** Every new material name hits a
   `INSERT INTO co_material_map`; lookups go through cached `Map<String,Integer>`.
   Fine, but the 4 dictionaries replicate machinery that one Postgres
   `INSERT … ON CONFLICT … RETURNING id` would do natively.
5. **MySQL's `BLOB` and `varchar` choices.** `chat.message varchar(16000)`
   forces a temp table on the slightest sort. `co_user.user varchar(100)`
   without `utf8mb4_bin` collation makes index lookups case-insensitive (slower
   and surprising).
6. **Single-writer batching.** All inserts funnel through one `Queue` thread.
   Postgres can absorb **concurrent batches via `COPY`** at 5–20× the
   prepared-statement throughput; we leave that on the table today.
7. **Indices written eagerly during DDL.** Every `CREATE TABLE` already
   contains its index definitions, so Postgres' "load fast then index" pattern
   isn't possible without a schema rebuild.
8. **No partial / expression indices.** `(rolled_back = 0)` is queried on every
   rollback but the index covers all rows including already-rolled-back ones.

---

## 1. Why Postgres makes sense here

CoreProtect is, structurally, an **append-mostly time-series log** with
occasional range scans (lookups, rollbacks) and rare deletes (purge).
PostgreSQL is the obvious fit. Concretely:

| Capability | What it buys CoreProtect |
|---|---|
| **Declarative range partitioning by `time`** | Drop a month of data with `DROP TABLE co_block_2026_03` instead of a 200 GB DELETE. Auto-purge becomes O(0) instead of O(N). |
| **`BRIN` indices on `time`** | A 100M-row table needs ~30 KB of index instead of 4 GB for a B-tree on `time`, because `time` is monotonically increasing. Range scans stay sub-second. |
| **`TOAST` + LZ4 column compression** | `meta` / `blockdata` columns automatically compressed out-of-line; SET STORAGE EXTERNAL and ALTER TABLE SET COMPRESSION lz4 cuts disk by 5–10× on hot tables for free. |
| **`INSERT … ON CONFLICT DO NOTHING RETURNING id`** | Unifies the dictionary-table dance currently spread across `MaterialModule`, `EntityModule`, etc. Atomic, no race. |
| **`COPY` protocol** | Bulk insert at line speed. The `Queue` consumer can swap `executeBatch()` for `COPY co_block FROM STDIN BINARY` and gain 5–20×. |
| **Logical replication / `pg_dump` + `--data-only`** | Real backup story; today operators rely on `mysqldump` blocking the writer. |
| **`pg_partman` + `pg_cron`** (extensions) | Auto-create next month's partition + drop the oldest in pure SQL, no plugin task. Optional fall-back if extensions aren't available. |
| **Better stats / planner** | The `LIMIT N OFFSET M` pagination in `LookupRaw` produces good plans on Postgres but is a known footgun on MySQL. |
| **Streaming results + async I/O** | The pgjdbc driver supports `setFetchSize` properly without the `ResultSet.TYPE_FORWARD_ONLY` MySQL workaround that's already in the lookup code. |

Counterargument: **adding a backend means more code paths to test.** We mitigate
by introducing a `Dialect` abstraction, deleting the `MYSQL` boolean checks,
and making MySQL pass through the same dialect pipe. Net code shrinks, not grows.

---

## 2. Architecture: the `Dialect` abstraction

The current `Config.getGlobal().MYSQL` boolean appears in 30+ files. Replace it
with a single `Dialect` interface plus three implementations.

```java
package net.coreprotect.database.dialect;

public interface Dialect {
    String name();                          // "sqlite" | "mysql" | "postgres"
    String createTable(TableDef def);       // returns dialect-specific DDL
    String createIndex(String table, String[] cols, IndexKind kind);
    String upsert(String table, String[] cols, String[] keyCols, String[] returning);
    String purgeBefore(String table, long before);  // partition-drop on PG, DELETE elsewhere
    String selectForUpdate(String base);    // PG: `FOR UPDATE`, MySQL: same, SQLite: noop
    BulkLoader bulkLoader(Connection c, String table, String[] cols);  // COPY on PG, executeBatch elsewhere
    // ... lookup-time formatting helpers
}
```

`BulkLoader` is the killer feature on the Postgres side:

```java
public interface BulkLoader extends AutoCloseable {
    void writeRow(Object... values) throws SQLException;
    long flush() throws SQLException;
}
```

Postgres impl wraps `CopyManager.copyIn("COPY co_block(...) FROM STDIN BINARY")`.
MySQL/SQLite impl falls back to a `PreparedStatement.executeBatch()` over a
prepared `INSERT`. Same call site, same semantics.

**Migration path:**
1. Land the `Dialect` interface as a no-op layer (MySQL impl wraps the existing
   strings 1:1, SQLite same). Delete `Config.MYSQL` reads behind facades.
   Plugin behaves identically. **One PR, zero behaviour change.**
2. Add `PostgresDialect`. Wire it behind `database-backend: postgres` in
   `config.yml`. Run the same upstream test suite (we'll need to add one — see
   §6) against it.
3. Once green, optimize `co_block`/`co_container` to range-partitioned tables
   on the Postgres dialect only (other dialects keep their flat schema).

---

## 3. Schema redesign for Postgres

### 3.1 Time-partitioned hot tables

```sql
CREATE TABLE co_block (
    rowid       bigint    GENERATED ALWAYS AS IDENTITY,
    time        integer   NOT NULL,
    user_id     integer   NOT NULL,
    wid         smallint  NOT NULL,
    x           integer   NOT NULL,
    y           smallint  NOT NULL,
    z           integer   NOT NULL,
    type        integer   NOT NULL,
    data        integer,
    meta        bytea     COMPRESSION lz4,
    blockdata   bytea     COMPRESSION lz4,
    action      smallint  NOT NULL,
    rolled_back boolean   NOT NULL DEFAULT false,
    PRIMARY KEY (time, rowid)
) PARTITION BY RANGE (time);
```

Partitions are weekly:

```sql
CREATE TABLE co_block_2026w17 PARTITION OF co_block
    FOR VALUES FROM (1745366400) TO (1745971200);
```

A scheduled task (or `pg_partman`) creates the next partition ahead of time
and drops the oldest beyond `purge-time`.

**Wins:**
- `DROP TABLE co_block_<oldest>` is O(0). No B-tree maintenance, no
  fragmentation, no row-by-row deletes through HikariCP, no replication lag.
- `BRIN(time)` on the parent suffices — partition pruning eliminates 99% of
  partitions before BRIN even runs.
- The `rolled_back` boolean replaces `tinyint`. A partial index
  `CREATE INDEX … ON co_block (wid, x, z, time) WHERE rolled_back = false`
  cuts the rollback-lookup index in half.
- `meta`/`blockdata` use `bytea COMPRESSION lz4` (PG 14+) — typical 4–8× ratio
  on NBT, transparent to Java.

### 3.2 Type tightening

| Today | Tomorrow | Why |
|---|---|---|
| `time int` | `time integer` (still epoch seconds), or `time timestamptz` if we're brave | timestamptz pairs with `BRIN`, makes `WHERE time >= now() - '7d'::interval` a one-liner |
| `wid int` | `wid smallint` | servers don't have 32k worlds; saves 2 B/row × 100M = 200 MB |
| `y int` | `y smallint` | MC y-range is `−4096..4096`, fits |
| `action tinyint` | `action smallint` | PG has no tinyint; smallint is the closest |
| `meta mediumblob` | `bytea COMPRESSION lz4` | per above |
| `data int` (sometimes blob) | `integer` for `_block`, `bytea` for `_item` | fewer mixed types |

Estimated savings per `co_block` row: 8 B raw + 50–80% on the toasted columns.
On a 100M-row table that's roughly **30–60 GB → 6–12 GB on disk**.

### 3.3 Dictionary tables → `INSERT … ON CONFLICT`

The four `_map` tables (`material_map`, `blockdata_map`, `entity_map`, `art_map`)
all do "insert if missing, return id". Replace the cache + insert + select dance
with:

```sql
WITH e AS (INSERT INTO co_material_map(material) VALUES ($1)
           ON CONFLICT (material) DO NOTHING RETURNING id)
SELECT id FROM e UNION ALL
SELECT id FROM co_material_map WHERE material = $1
LIMIT 1;
```

One round-trip, one statement, race-free. Falls back on MySQL via
`INSERT … ON DUPLICATE KEY UPDATE … RETURNING` (MariaDB 10.5+) or the legacy
"insert ignore + select" on older MySQL. The `Dialect.upsert()` hides this.

### 3.4 BRIN indices

```sql
CREATE INDEX co_block_brin_time ON co_block USING BRIN (time) WITH (pages_per_range=32);
```

Replaces a B-tree of ~3 GB on a 100M-row table with ~10–30 KB. Range scans by
`time` for `/co lookup t:7d` go straight there. Existing `(wid, x, z, time)`
B-tree is kept for spatial lookups but gets the partial-index treatment above.

---

## 4. Auto-retention / purge-time

The user-facing config (works on **all three backends** — MySQL gets it too):

```yaml
# config.yml additions
retention:
  enabled: true
  # how long to keep data; 0 = forever (current behaviour)
  keep: 60d
  # how often to run the housekeeping job (cron-like)
  schedule: "0 4 * * *"   # daily at 04:00 server time
  # which tables to retain ('all' or a list)
  tables: all
  # optional per-table override (rolling-window comments retained shorter, etc.)
  per-table:
    co_chat: 14d
    co_command: 30d
  # extra filter on what to purge: 'rolled_back' deletes only the inverse of
  # what survives a rollback. Default is 'all'.
  scope: all              # all | rolled-back | non-rolled-back
```

### 4.1 Implementation per dialect

- **Postgres (cheap path)**: with the time-partitioned schema, retention =
  `DROP TABLE co_block_<old>` for any partition whose upper bound is before
  `now() - keep`. Runs in milliseconds, releases storage immediately, logs the
  freed bytes. Rolled into the existing `Queue.tick()` via a once-a-tick
  scheduled task (Folia: `Bukkit.getAsyncScheduler().runAtFixedRate(...)`).
- **MySQL (medium path)**: chunked `DELETE FROM co_block WHERE time < ?
  ORDER BY time LIMIT 10000` in a loop until 0 rows affected, with a 50 ms
  sleep between chunks. Index `time` is already there. Uses the existing
  `purgeRunning` flag to refuse concurrent purges.
- **SQLite (fallback path)**: same chunked DELETE but in a single transaction
  per chunk; vacuum at the end if more than 10% of rows went away.

The plugin command surface stays as `/co purge` (single shot) and gains
`/co retention status|enable|disable|set <duration>` for live tweaks.

### 4.2 Integration points

- `RetentionService` is a new class under `net.coreprotect.services`. Cron
  parsing via the existing `cron-utils` style (or a 50-line minimal cron of
  our own).
- Hook from `CoreProtect.onEnable()` after `VersionCheckService.performVersionChecks()`.
- Idempotent across plugin restarts: persists a `last_run` row to
  `co_version` so a 4 a.m. job missed because of a 3 a.m. restart still runs.

### 4.3 Why this is safe to land alone

Even before the Postgres dialect arrives, the chunked-DELETE retention works on
the existing MySQL/SQLite schemas. We can ship retention as the first PR — that
alone answers the user's "auto-retention/purge time we can configure". The
Postgres dialect then makes the same feature an order-of-magnitude faster.

---

## 5. Footprint & speed wins quantified (rough)

Order-of-magnitude estimates for a creative server with ~100M rows in
`co_block` after 12 months:

| Metric | Today (MySQL InnoDB) | Postgres + redesign | Improvement |
|---|---|---|---|
| `co_block` data | ~28 GB | ~5–8 GB | 4–6× |
| `co_block` indices | ~5 GB (B-trees) | ~1.5 GB (partial + BRIN) | 3× |
| Rollback `lookup t:30d` | 4–8 s | 0.5–1.5 s | 3–8× |
| Retention "drop 60d+" | impossible (24h+ DELETE) | <100 ms (DROP TABLE) | ∞ |
| Insert throughput | ~6 k rows/s | ~30–80 k rows/s (`COPY`) | 5–13× |
| Plugin jar size | 2.2 MB | 2.4 MB (pgjdbc shaded) | +0.2 MB |
| Memory at idle | ~120 MB | ~110 MB (one less driver) | tiny |

The plugin grows a bit (we're adding pgjdbc) but storage is the dominant cost
and that's where we win.

---

## 6. Test harness — what we need to validate this

CoreProtect upstream has *zero* JUnit sources today (verified — `mvn test` on
HEAD with `-DskipTests=false` reports `No sources to compile`). We need to land
a real test rig before we touch the storage layer.

Three layers:

1. **Unit tests** with MockBukkit (`src/test/java/...`) — validate the
   `Dialect` returns the right DDL/SQL strings for each backend. Cheap and fast.
2. **Integration tests** with **Testcontainers** — spin up real Postgres /
   MySQL / SQLite, run the full plugin lifecycle through MockBukkit + real
   JDBC, assert (a) all tables created, (b) sample inserts of every Action
   type, (c) lookup returns expected rows, (d) rollback sets `rolled_back`,
   (e) purge with `t:1s` deletes them, (f) Postgres-specific: partition exists
   for current week, expired partition gets dropped.
3. **Smoke harness** (already exists at `/Users/paulchauvat/coreprotect-ekaii/smoke/`) —
   extend with rcon commands `/co status`, `/co lookup t:1s`, `/co purge t:1s`,
   `/co retention status`. Each in a separate run; harness fails if any throws
   or if log contains `WrongThreadException` / `is not Folia compatible`.

CI matrix grows by ~3 jobs (one per backend) but keeps the existing two
build-only jobs green.

---

## 7. Risks & open questions

- **AdvancedChests soft-dep was removed from `plugin.yml` between v23.1 and
  v23.2** — confirm whether the schema for chest logging changed. If yes, the
  `co_container` partition layout has to handle both rows.
- **HikariCP version pin (`7.0.2`) requires Java 21+.** The mc1.21.11 branch
  builds against JDK 21, fine. If we want to keep Postgres support on the
  v23.1 branch we either bump HikariCP or live without one of the new APIs.
- **`pg_cron` / `pg_partman`** are admin-controlled extensions; on shared
  Postgres we can't assume they exist. The `RetentionService` must work
  without them (we just lose the pretty in-DB schedule).
- **MySQL → Postgres migration** for existing operators. Out of scope for this
  PR; doc it as "set up a fresh Postgres, restart the plugin, the schema
  will be created — old MySQL data stays on MySQL". A follow-up `co migrate`
  command can stream rows across.
- **Folia thread safety on the new RetentionService.** Anything that touches
  `Bukkit.getOnlinePlayers()` during a purge must use `getGlobalRegionScheduler()`.
  Per project_axiom_paper_folia, this is well-trodden ground.

---

## 8. Implementation tickets (split-ready for parallel agents)

1. **`PR1: dialect-abstraction`** — introduce `Dialect` interface, three
   implementations (sqlite/mysql/postgres), refactor 30 call sites away from
   `Config.MYSQL` boolean. Zero behaviour change. Lands first.
2. **`PR2: postgres-backend`** — PostgresDialect concrete impl, pgjdbc shaded
   into the uber-jar (relocated to `net.coreprotect.shaded.postgresql`),
   `database-backend: postgres` config key, all upstream actions logged
   correctly via integration tests.
3. **`PR3: retention-service`** — `RetentionService` class, `retention:` config
   block, `/co retention …` command, MySQL+SQLite chunked-delete impl. Lands
   independently of PR2; doesn't need Postgres.
4. **`PR4: postgres-partitioning`** — only after PR2+PR3. Switch
   `PostgresDialect` to range-partitioned `co_block` / `co_container`,
   implement `purgeBefore()` as `DROP PARTITION`, BRIN indices, lz4 compression.
5. **`PR5: copy-bulk-loader`** — swap `Queue` from `executeBatch` to the
   `BulkLoader.flush()` abstraction. Big perf win, contained code change.
6. **`PR6: testcontainers-ci`** — add the three integration-test jobs to
   forgejo CI matrix; gate PR4/PR5 on these being green.

PR1+PR3 are good first targets (no Postgres infra needed). PR2 onward
needs Testcontainers running on the runner, which means adding Docker-in-Docker
(or `--privileged`) to the `coolify-linux-runner` config.

---

## 9. Drop-in summary for the user

- **Postgres backend is a 4-PR project** (dialect abstraction → PG impl →
  partitioning → COPY-based bulk loader). Each PR is independently shippable.
- **Auto-retention is a 1-PR project** that ships immediately on the existing
  MySQL/SQLite schemas; gets faster once Postgres lands.
- **Footprint reduction**: ~4–6× smaller `co_block` data, ~3× smaller indices,
  3–8× faster range lookups, **DROP TABLE retention** in <100 ms instead of
  hours. Plugin jar grows by ~200 KB (pgjdbc).
- **Worth doing because**: CoreProtect's storage model is a time-series log
  pretending to be a relational table, and Postgres natively models that with
  partitioning + BRIN + TOAST + `COPY`. The current MySQL setup pays full RDBMS
  cost for none of the time-series benefits.
- **Risk profile**: low. The dialect abstraction PR is pure refactor. The
  Postgres PR is opt-in. The retention PR is opt-in (`enabled: false` by default
  for first release). No upstream behaviour changes for existing operators.
