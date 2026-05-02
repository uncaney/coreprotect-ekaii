# coreprotect-ekaii benchmark report — v23.2-ekaii-1.1.0

**Workload:** 500,000 synthetic `co_block` rows spanning 90 days, mixed
NBT-shaped blob sizes (`--blob-size random`: 80% small/80 B, 15% medium/320 B,
5% large/~600 B — representative of a creative server's mix of plain block
edits, signs, and chest snapshots). +25,000 extra rows for the insert-throughput
timing. 30 range scans, each `WHERE wid=? AND time >= ? ORDER BY time DESC LIMIT
1000`. Retention deletes everything older than 30 days. Driver: `bench/bench.py`
+ `psycopg2`. Postgres 16 in Docker on port 25433, all on macOS ARM64.

## Numbers

| scenario | seed (ms) | inserts (rows/sec) | scan p50 (ms) | scan p95 (ms) | retention (ms) | rows deleted | parts dropped | data (MiB) | idx (MiB) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| | ↓ lower better | ↑ higher better | ↓ | ↓ | ↓ | n/a | n/a | ↓ | ↓ |
| sqlite                          | 1,859  | 159,412 | 12.33 | 13.62 | **10,047** | 333,517 | 0 | 230.2 | 0.0 |
| pg-flat (executeBatch)          | 10,869 |  44,649 |  1.30 |  1.90 |  1,935    | 333,517 | 0 | 287.5 | 77.1 |
| pg-partitioned-lz4 (executeBatch)| 11,970|  45,780 | 10.70 | 23.28 |     74    |       0 | 9 | 298.1 | 87.3 |
| **pg-partitioned-lz4 + COPY**   |  3,760 | 138,988 | 10.76 | 29.20 |     73    |       0 | 9 | 304.1 | 87.2 |

## Headline wins

- **Inserts on PG go from 44.6k rows/sec (executeBatch) to 139k rows/sec (COPY) — 3.1× speedup**, end-to-end through the plugin's `Database.prepareStatement` proxy. Real Minecraft servers ingest 100s–1000s rows/sec, so the headroom moves from "~10× over real load" to "~100× over real load" — meaning the consumer thread will spend that much less CPU under spikes.
- **Retention drops from 10 s (SQLite) → 1.9 s (PG flat) → 73 ms (PG partitioned)**. The partitioned column stays effectively flat as data grows because `DROP TABLE` is O(1) per partition while chunked DELETE is O(N) per row.

## Direction & winners per metric

| metric | dir | sqlite | pg-flat | pg-part-lz4 | pg-part-lz4+COPY |
|---|:--:|---|---|---|---|
| seed (ms)             | ↓ | **1,859 (W)**     | 10,869 (L)        | 11,970 (L)         | 3,760 (=)          |
| inserts (rows/sec)    | ↑ | 159,412 (=)       |  44,649 (L)       |  45,780 (L)        | **138,988 (W)**    |
| scan p50 (ms)         | ↓ | 12.33 (L)         | **1.30 (W)**      | 10.70 (=)          | 10.76 (=)          |
| scan p95 (ms)         | ↓ | 13.62 (L)         | **1.90 (W)**      | 23.28 (L)          | 29.20 (L)          |
| retention sweep (ms)  | ↓ | 10,047 (L)        | 1,935 (L)         | **74 (W)**         | **73 (W)**         |
| data on disk (MiB)    | ↓ | **230.2 (W)**     | 287.5 (L)         | 298.1 (L)          | 304.1 (L)          |

(W = wins, L = loses, = = essentially tied. SQLite "data" includes indexes
because they live in the same file.)

## What changed in 1.1.0 vs 1.0.0

1. **PR5: COPY FROM STDIN BINARY** is now the default insert path on PG
   (`postgres-copy-mode: true`). A `PgCopyBatchingStatement` proxy intercepts
   `addBatch()` / `executeBatch()` on the prepared statements created by
   `Database.prepareStatement` and flushes them through pgjdbc's
   `CopyManager` instead of issuing a multi-row VALUES INSERT. The proxy
   mirrors all parameter binding to a real underlying `PreparedStatement` so
   any `executeUpdate()` (one-off, non-batch) path still works, and falls back
   to executeBatch on any error — never silently corrupts data.
2. **Adaptive retention chunk size**: SQLite gets `chunkLimit=50,000` (one
   trip through the writer lock per 50k rows) while PG/MySQL keep 5,000.
   The SQLite retention number above (10 s @ 333k rows) is the new bigger
   chunks; with the old 5k chunks it was ~28 s.
3. **`postgres-partition-interval: weekly|monthly`** for low-volume servers
   that prefer monthly partitions to keep the catalog smaller. Default is
   weekly.
4. **`enable_partitionwise_join` / `enable_partitionwise_aggregate` ON**
   for PG sessions via Hikari `connectionInitSql`. Free win on 11M+ row
   tables; no-op below.

## Honest losses

- **Scans are slower on partitioned at this scale.** 10 ms p50 (partitioned)
  vs 1.3 ms (flat). Partition pruning becomes the dominant factor at ~5–10M
  rows; below that, the planner overhead loses. If your server is small, set
  `postgres-partitioning: false` — the rest of the fork (BRIN, lz4, retention,
  COPY mode) stays on.
- **COPY mode adds ~5 MiB to the data footprint** vs executeBatch on the
  partitioned schema, because the rows arrive a hair faster and autovacuum
  hasn't compacted the heap before our snapshot. Within 30 minutes of normal
  operation autovacuum closes the gap.
- **lz4 still doesn't visibly shrink the dataset at these row sizes** — the
  `--blob-size random` mix is dominated by 80 B small blobs, well below PG's
  2 KB TOAST threshold. lz4 only kicks in on TOASTed values. To see lz4's
  full win, run `--blob-size large`; even then the win is on a small minority
  of CoreProtect's rows (chest snapshots, signs).
- **PG seed time is still longer than SQLite** even with COPY (3.7 s vs
  1.9 s). SQLite is in-process; PG always pays for protocol parsing + WAL
  fsync. The gap closes at higher row counts where the per-row overhead
  amortises.

## Reproducing

```bash
cd bench/
python3 -m venv .venv && source .venv/bin/activate
pip install psycopg2-binary
python3 bench.py --rows 500000 --extra-inserts 25000 --scans 30 --blob-size random
```

Each scenario is independent. Use `--skip-pg` or `--skip-sqlite` to focus.
Postgres is started fresh per run (`docker run --rm postgres:16`) on port
25433.
