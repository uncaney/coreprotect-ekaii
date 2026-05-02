# Smoke harness

Light end-to-end test that boots a Folia/Luminol server, drops the freshly
built CoreProtect jar in, watches for a clean enable + retention sweep, then
shuts down. Used after every dialect/proxy change to catch regressions before
they reach the bench or release.

## Layout

You provide:
- A Folia or Luminol paperclip jar (download or build).
- A working JDK matching the MC version (JDK 21 for 1.21.x, JDK 25 for 26.x).

Workspace per scenario:
```
smoke/
  run-smoke.sh                   # 30-second clean-load test
  run-smoke-retention.sh         # 100-second retention-fires test
  myserver/
    eula.txt                     # eula=true
    luminol-paperclip-26.1.2.jar
    plugins/
      CoreProtect-23.2-mc26.1.2.jar
      CoreProtect/config.yml     # backend selection
```

## Quickstart

Default-SQLite scenario:

```bash
mkdir -p smoke/myserver/plugins
cp dist/CoreProtect-23.2-mc26.1.2.jar smoke/myserver/plugins/
echo "eula=true" > smoke/myserver/eula.txt
# Drop a Folia/Luminol paperclip jar into smoke/myserver/, e.g. luminol-paperclip-26.1.2.jar
bash smoke/run-smoke.sh myserver luminol-paperclip-26.1.2.jar /path/to/jdk25 "default-sqlite"
```

PASS criteria:
- Server reaches `Done (` within 120 s.
- `[CoreProtect] Enabling CoreProtect v23.2` appears.
- `[CoreProtect] CoreProtect Community Edition has been successfully enabled!`.
- No `Could not load`, `Disabled CoreProtect` (before shutdown), or
  unhandled `Exception` lines in `run.log`.

## Postgres scenario

Spin up an ephemeral PG via Docker:

```bash
docker run -d --rm --name cp-pg-smoke -p 25432:5432 \
  -e POSTGRES_PASSWORD=cp -e POSTGRES_USER=cp -e POSTGRES_DB=coreprotect \
  postgres:16
# Wait ~3s for PG to be ready
cat > smoke/myserver/plugins/CoreProtect/config.yml <<YAML
database-backend: postgres
postgres-host: 127.0.0.1
postgres-port: 25432
postgres-database: coreprotect
postgres-username: cp
postgres-password: cp
postgres-partitioning: true
postgres-copy-mode: true
retention-enabled: false
YAML
bash smoke/run-smoke.sh myserver luminol-paperclip-26.1.2.jar /path/to/jdk25 "pg-partitioned"
docker stop cp-pg-smoke
```

## Retention-fires scenario

`run-smoke-retention.sh` boots the server, waits 100 s for the cron
sweeper to fire (with `retention-schedule: '* * * * *'`), and asserts the
sweep ran. Useful when validating cron parsing, partition drop, or
chunked-DELETE chunkLimit changes.

```bash
# config.yml: retention-enabled: true, retention-keep: '1s', retention-schedule: '* * * * *'
bash smoke/run-smoke-retention.sh myserver luminol-paperclip-26.1.2.jar /path/to/jdk25 "ret-test"
grep "Retention sweep" smoke/myserver/run.log
```

## Why this exists

Upstream CoreProtect has no test harness — `src/test` is empty and `mvn test`
prints `No sources to compile`. Most regressions only show up when the
plugin actually loads on a real Bukkit/Folia instance. Smoke is the
minimum-viable validation between "it builds" and "ship it." Bench
(`bench/`) covers performance; smoke covers correctness.
