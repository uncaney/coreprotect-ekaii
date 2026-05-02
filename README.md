# coreprotect-ekaii

A fork of [PlayPro/CoreProtect](https://github.com/PlayPro/CoreProtect) that adds:

1. **Auto-retention** — the database stops growing forever. Single config flag,
   works on every backend.
2. **Optional PostgreSQL backend** with partitioning, BRIN indices, lz4
   compression, COPY-mode bulk inserts, and Unix-socket auto-detect.
3. **Optional DuckDB backend** for SQLite-style "single file, no install"
   deployments that want PG-class query performance.
4. **MC 1.21.11 + 26.1.2 builds** with reproducible CI.
5. **A real benchmark harness** so the performance numbers are verifiable, not
   marketing.

Upstream CoreProtect logic, commands, API, and rollback semantics are
**unchanged**. Existing operators see no behavioural difference unless they
explicitly opt in to the new backends or enable retention.

## Quick start

### Default (SQLite, recommended for <30 active players)

Drop the jar in `plugins/` and start the server:

```bash
curl -fsSL -O https://forgejo.ekaii.fr/exo/coreprotect-ekaii/releases/download/v23.2-ekaii-1.2.0/CoreProtect-23.2-mc26.1.2.jar
mv CoreProtect-23.2-mc26.1.2.jar /path/to/server/plugins/
```

Available builds:

| File | MC versions | Build JDK | Runs on |
|---|---|---|---|
| `CoreProtect-23.2-mc26.1.2.jar` | 26.1.2 / Folia 26.x | JDK 25 | JDK 11+ |
| `CoreProtect-23.1-mc1.21.11.jar`| 1.21.11 / Folia 1.21.x | JDK 21 | JDK 11+ |

### Enable auto-retention

```yaml
# plugins/CoreProtect/config.yml
retention-enabled: true
retention-keep: 60d                # 30d, 8w, 6mo, 1y all parse
retention-schedule: '0 4 * * *'    # 5-field UTC cron
```

`/co retention status|enable|disable|set <duration>|run` for live changes.
On SQLite/MySQL the sweep is chunked DELETE (5,000 rows/chunk on PG/MySQL,
50,000 on SQLite, 50 ms inter-chunk pause). On PG with partitioning, stale
weekly partitions are dropped via `DROP TABLE` (O(1) per partition).

### Switch to Postgres

```yaml
database-backend: postgres
postgres-host: 127.0.0.1
postgres-port: 5432
postgres-database: coreprotect
postgres-username: coreprotect
postgres-password: ...
postgres-partitioning: true        # weekly partitions, DROP-PARTITION retention
postgres-lz4: true                 # PG14+: lz4 compress meta/blockdata blobs
postgres-copy-mode: true           # COPY FROM STDIN BINARY for inserts
postgres-async-commit: false       # set true to trade fsync for ~2x throughput
```

### Switch to DuckDB

```yaml
database-backend: duckdb
duckdb-path: database.duckdb
```

The DuckDB JDBC driver is **not bundled** (it's 73 MB with native binaries
for all platforms). Drop it into Paper's `libraries/` folder yourself:

```bash
cd /path/to/server/libraries/
curl -fsSL -O https://repo1.maven.org/maven2/org/duckdb/duckdb_jdbc/1.1.3/duckdb_jdbc-1.1.3.jar
```

If the driver is missing, the plugin logs a clear console message and falls
back to SQLite.

## What you actually get

For a small server (1–10 active players): **auto-retention is the only feature
that helps you**. SQLite is the right pick — fastest cold start, smallest RAM
footprint, no separate service. The PG/DuckDB work is overkill at this scale
and may even hurt (cold start, idle RAM).

For a busy server (30+ active players, 1M+ rows in `co_block`): all four PR
families pay off:

| metric | SQLite | DuckDB | PG-flat | PG-partitioned + COPY |
|---|--:|--:|--:|--:|
| inserts (rows/sec)         | 207k | 362k | 44k  | 130k |
| scan p50 (ms)              | 18.8 | 2.0  | 1.2  | 1.4  |
| scan p95 (ms)              | 20.8 | 2.4  | 2.5  | 2.6  |
| retention sweep (ms)       | 27.2k| 1.4k | 5.5k | **93** |
| data on disk (MiB)         | 460  | 424  | 574  | 603  |

(at 1 million synthetic rows with a realistic mixed-NBT-blob distribution.
Full report including 500k-row numbers, methodology, and reproduction
commands: [`bench/REPORT.md`](bench/REPORT.md).)

PG with partitioning is the only setup where retention is **O(1) per stale
partition** (`DROP TABLE`) instead of O(N) per stale row (`DELETE`). At 1M
rows that's **290× faster than SQLite**; at 30M rows it's still 100 ms.

## Reproducible benchmark

```bash
git clone https://forgejo.ekaii.fr/exo/coreprotect-ekaii
cd coreprotect-ekaii/bench

python3 -m venv .venv
source .venv/bin/activate
pip install psycopg2-binary duckdb pyarrow

# Default 500k-row run
python3 bench.py --rows 500000 --extra-inserts 25000 --scans 30 --blob-size random

# 1M-row "battle test"
python3 bench.py --rows 1000000 --extra-inserts 50000 --scans 50 --blob-size random
```

Each scenario (`sqlite`, `duckdb`, `pg-flat`, `pg-partitioned-lz4`,
`pg-partitioned-lz4-copy`) is independent. Postgres runs in an ephemeral
Docker container per run on port 25433; DuckDB and SQLite use temp files
under `bench/results/`. Output is a single Markdown table on stdout plus a
JSON file under `bench/results/bench-<UTC>.json`.

`--skip-pg`, `--skip-sqlite`, `--skip-duckdb` skip individual scenarios.
`--blob-size {small|medium|large|random}` selects payload distribution
(80%/15%/5% mix on `random`, representing block edits / signs / chest
snapshots).

## Smoke test

A separate harness boots a real Folia/Luminol server, loads the freshly built
jar, watches for a clean enable + retention sweep, then shuts down. Used as
the minimum viable validation between "it builds" and "ship it" — covers
correctness; the bench covers performance.

```bash
mkdir -p smoke/myserver/plugins
cp dist/CoreProtect-23.2-mc26.1.2.jar smoke/myserver/plugins/
echo "eula=true" > smoke/myserver/eula.txt
# Drop a Folia/Luminol paperclip jar into smoke/myserver/
bash smoke/run-smoke.sh myserver luminol-paperclip-26.1.2.jar /path/to/jdk25 "default-sqlite"
```

Details + Postgres + retention scenarios in [`smoke/README.md`](smoke/README.md).

## Architecture

The fork's design lives in [`DESIGN.md`](DESIGN.md). Short version:

- **`net.coreprotect.database.dialect.Dialect`** is the single interface every
  storage decision flows through: DDL, transaction keywords, retention
  strategy. Concrete implementations: `SqliteDialect`, `MysqlDialect`,
  `PostgresDialect`, `DuckDBDialect`. Replaces 30+ scattered
  `Config.MYSQL` boolean checks in upstream.

- **`net.coreprotect.database.dialect.PgConnectionProxy`** is a JDK
  `Proxy<Connection>` that intercepts SQL strings and rewrites MySQL-isms
  (`LIMIT n,m → LIMIT m OFFSET n`, bare `user → "user"`) at the JDBC layer
  so existing SQL builders work on PG without source changes.

- **`net.coreprotect.database.dialect.PgCopyBatchingStatement`** is a JDK
  `Proxy<PreparedStatement>` that intercepts `addBatch()` / `executeBatch()`
  on bulk INSERT statements and flushes via pgjdbc's `CopyManager`. 3.1×
  faster than executeBatch on PG.

- **`net.coreprotect.services.PartitionService`** manages weekly (or monthly)
  range partitions for `co_block` / `co_container` on Postgres. `ensureUpcoming(N)`
  creates the next N partitions on plugin enable; `dropOlderThan(unix)` is the
  O(1) retention path.

- **`net.coreprotect.services.RetentionService`** runs the cron-driven sweep.
  Folia-aware async scheduler. `ReentrantLock` prevents overlapping sweeps.
  Hikari connection rotation every 10 chunks dodges `maxLifetime` eviction.

## Documentation

- [`DESIGN.md`](DESIGN.md) — architecture, why each PR exists, performance estimates and how they were validated
- [`bench/REPORT.md`](bench/REPORT.md) — full benchmark numbers + reproduction commands
- [`bench/results/`](bench/results/) — raw JSON from every recorded run
- [`smoke/README.md`](smoke/README.md) — end-to-end correctness harness
- [`HANDOFF.md`](HANDOFF.md) — workspace + build instructions for contributors
- [`CHANGELOG.md`](CHANGELOG.md) — release-by-release breakdown

## Releases

Source: https://forgejo.ekaii.fr/exo/coreprotect-ekaii/releases (mirror: https://github.com/uncaney/coreprotect-ekaii/releases)

| Tag | Headline |
|---|---|
| `v23.2-ekaii-1.0.0` | Postgres backend + auto-retention + partitioning |
| `v23.2-ekaii-1.1.0` | COPY-based inserts (3.1× faster) + cheap fixes |
| `v23.2-ekaii-1.2.0` | DuckDB backend, beats SQLite on every metric at 1M rows |

Each release attaches both jar variants, the benchmark report, and the raw
JSON for the headline numbers.

## License

Same as upstream CoreProtect: Artistic License 2.0. See [`LICENSE`](LICENSE).
Upstream maintainer: Intelli + contributors. Fork by exo / paul chauvat.
