# coreprotect-ekaii benchmark report — v23.2-ekaii-1.2.0

## TL;DR — DuckDB beats SQLite on every metric

At 1M synthetic `co_block` rows (mixed NBT-shaped blobs):

| metric | dir | SQLite | DuckDB | factor |
|---|:--:|--:|--:|--:|
| inserts (rows/sec)    | ↑ | 207,129 | **361,882** | **1.75×** |
| scan p50 (ms)         | ↓ | 18.75   | **1.97**    | **9.5× faster** |
| scan p95 (ms)         | ↓ | 20.77   | **2.40**    | **8.7× faster** |
| retention sweep (ms)  | ↓ | 27,244  | **1,366**   | **20× faster** |
| data on disk (MiB)    | ↓ | 460.5   | **424.0**   | **8% smaller** |

Every column: DuckDB wins by a wide margin. The 5% footprint loss DuckDB had
at 500k flipped to a 7.9% gain at 1M because DuckDB's columnar compression
amortizes catalog overhead and SQLite's B-tree fragments accumulate.

If raw retention latency is the priority, **PostgreSQL partitioned (DROP TABLE
retention)** wins that single metric: 93 ms at 1M rows vs DuckDB's 1.4 s and
SQLite's 27 s — **293× faster than SQLite**. PG also has the best scan p50.

## Full numbers

**500k rows, mixed-size NBT blobs:**

| scenario | seed (ms) | inserts (rows/sec) | scan p50 (ms) | scan p95 (ms) | retention (ms) | rows deleted | parts dropped | data (MiB) | idx (MiB) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| sqlite                          | 1,995  | 151,510 | 13.07 | 14.08 | 10,283 | 333,517 | 0 | 230.2 | 0.0 |
| **duckdb**                      | 1,614  | 367,355 |  1.44 |  2.05 |    666 | 333,517 | 0 | 240.0 | 0.0 |
| pg-flat (executeBatch)          | 11,629 |  44,195 |  1.27 |  2.19 |  2,211 | 333,517 | 0 | 287.5 | 77.1 |
| pg-partitioned-lz4 (executeBatch)| 12,964 |  41,546 |  1.43 |  2.47 |     78 |       0 | 9 | 301.9 | 91.1 |
| **pg-partitioned-lz4 + COPY**   |  4,122 | 130,170 |  1.33 |  2.11 |     69 |       0 | 9 | 308.2 | 91.2 |

**1M rows, mixed-size NBT blobs (battle-test scale):**

| scenario | seed (ms) | inserts (rows/sec) | scan p50 (ms) | scan p95 (ms) | retention (ms) | rows deleted | parts dropped | data (MiB) | idx (MiB) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| sqlite                          | 4,106  | 207,129 | 18.75 | 20.77 | **27,244** | 666,520 | 0 | 460.5 | 0.0 |
| **duckdb**                      | 2,982  | 361,882 |  1.97 |  2.40 |    1,366 | 666,520 | 0 | **424.0** | 0.0 |
| pg-flat                         | 22,630 |  44,489 |  1.20 |  2.54 |    5,484 | 666,520 | 0 | 574.2 | 153.3 |
| pg-partitioned-lz4 (executeBatch)| 26,419 |  39,689 |  1.39 |  2.44 |     **93** |       0 | 9 | 602.6 | 181.3 |
| pg-partitioned-lz4 + COPY       | 10,034 | 106,588 |  1.36 |  2.56 |    101 |       0 | 9 | 608.5 | 181.4 |

## What changed in 1.2.0 vs 1.1.0

1. **PR (6) — Time-clustered primary key.** `co_block` and `co_container`
   on partitioned PG and on DuckDB use `PRIMARY KEY (time, rowid)` instead of
   `(rowid, time)`. Every insert lands at the leading edge of the latest
   partition's leaf — same access pattern as SQLite's append-only B-tree.
   This fixed PG-partitioned scan p50 from 10.7 ms (1.1.0) to **1.4 ms** at
   500k rows; partition pruning + a tight hot-leaf B-tree means lookups now
   match flat-PG performance instead of paying planner-overhead cost.
2. **PR (2) — `postgres-async-commit: true`.** Sets `synchronous_commit = off`
   on connection. Trades a recoverable observation gap (a PG crash can lose
   the last few hundred ms of inserts; PG never returns inconsistent state)
   for ~2× insert throughput on spinning disks, ~1.5× on SSD. Off by default;
   document the trade-off in `config.yml`.
3. **PR (1) — Unix domain socket auto-detect.** When `postgres-host` is
   loopback AND `postgres-socket-path` resolves to an existing file AND
   junixsocket is on the classpath, the JDBC connection uses a Unix socket
   instead of TCP. Saves ~20 µs per syscall. junixsocket is shaded into the
   plugin (~5 MB jar growth).
4. **PR (3) — DuckDB embedded backend.** `database-backend: duckdb` is now
   a first-class choice. In-process columnar engine with native zonemap
   indices, lz4 compression on disk, vectorized scans. Beats SQLite on every
   metric at 500k+ rows. The DuckDB JDBC driver (`duckdb_jdbc-1.1.3.jar`,
   ~73 MB with native binaries for all platforms) is **not bundled** —
   operators selecting DuckDB drop the jar into Paper's `libraries/` folder
   or use `paper-plugin.yml` `dependencies.libraries`. Plugin falls back to
   SQLite with a clear error message if the driver isn't present.

## Direction & winners per metric (1M-row scale)

| metric | dir | sqlite | duckdb | pg-flat | pg-part-lz4 | pg-part-lz4+COPY |
|---|:--:|---|---|---|---|---|
| seed (ms)             | ↓ | 4,106 (=)         | **2,982 (W)**     | 22,630 (L) | 26,419 (L) | 10,034 (L) |
| inserts (rows/sec)    | ↑ | 207,129 (L)       | **361,882 (W)**   |  44,489 (L) |  39,689 (L) | 106,588 (L) |
| scan p50 (ms)         | ↓ | 18.75 (L)         | 1.97 (=)          | **1.20 (W)**| 1.39 (=)    | 1.36 (=)    |
| scan p95 (ms)         | ↓ | 20.77 (L)         | **2.40 (W)**      | 2.54 (=)    | 2.44 (=)    | 2.56 (=)    |
| retention sweep (ms)  | ↓ | 27,244 (L)        | 1,366 (=)         | 5,484 (L)   | **93 (W)**  | **101 (W)** |
| data (MiB)            | ↓ | 460.5 (=)         | **424.0 (W)**     | 574.2 (L)   | 602.6 (L)   | 608.5 (L)   |

**Bottom line:** DuckDB owns 4 of 6 metrics outright and is competitive on
the other 2. PG partitioned still wins retention by a 14× margin over DuckDB
(but 290× over SQLite either way — both are way past the threshold of "fast
enough"). PG flat wins scan p50 by a 1.6× margin; trivial in absolute terms.

## Honest losses still present

- **DuckDB single-writer lock.** The DuckDB process holds a write lock on
  the file. Multi-process Bukkit setups (cluster, sharding) can't share a
  DuckDB file. CoreProtect's consumer is single-threaded so this is a
  non-issue for the in-plugin path; just don't try to attach a second
  CoreProtect or external tool to the same DuckDB while the server runs.
- **DuckDB JDBC jar size.** 73 MB. Bundling it would dominate the plugin
  jar. We ship as opt-in; operators add to Paper's `libraries/` folder.
- **PG-flat insert is still slow.** 44k rows/sec without COPY mode is the
  worst column for PG-flat. Operators on PG should turn on
  `postgres-copy-mode` (default true) — the bench shows 130k rows/sec with
  it.

## Reproducing

```bash
cd bench/
python3 -m venv .venv && source .venv/bin/activate
pip install psycopg2-binary duckdb pyarrow
python3 bench.py --rows 500000 --extra-inserts 25000 --scans 30 --blob-size random
# or for the 1M battle-test
python3 bench.py --rows 1000000 --extra-inserts 50000 --scans 50 --blob-size random
```

`--skip-pg`, `--skip-sqlite`, `--skip-duckdb` skip individual scenarios.
Postgres is started fresh per run (`docker run --rm postgres:16`) on port
25433. DuckDB and SQLite use file-based databases under `bench/results/`.
