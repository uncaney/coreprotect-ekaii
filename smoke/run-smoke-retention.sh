#!/usr/bin/env bash
# Boot for ~110s — long enough for the 1-minute cron to fire at least once.
set -uo pipefail
DIR="${1:-srv26}"
JAR="${2:-luminol-paperclip-26.1.2.local-SNAPSHOT.jar}"
JDK="${3:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
LABEL="${4:-pg-retention}"

cd "$(dirname "$0")/$DIR" || exit 2
LOG="$(pwd)/run.log"
: > "$LOG"

echo "[retention-smoke] booting $LABEL with $JAR ..." | tee -a "$LOG"
"$JDK/bin/java" -Xmx2G -Xms1G -Dcom.mojang.eula.agree=true -jar "$JAR" --nogui >>"$LOG" 2>&1 &
PID=$!

# Wait for Done(
WAIT=120
ELAPSED=0
DONE=0
while [ $ELAPSED -lt $WAIT ]; do
  if grep -qE 'Done \(' "$LOG" 2>/dev/null; then DONE=1; break; fi
  sleep 2
  ELAPSED=$((ELAPSED+2))
done
[ "$DONE" -ne 1 ] && { echo "FAIL: server never reached Done(" ; tail -30 "$LOG"; kill -9 $PID; exit 1; }

# Wait 110s for retention sweeper (cron */* fires once a minute, but tick runs every 60s
# starting from server boot; so we may need up to ~120s to be sure).
echo "[retention-smoke] server up, waiting 100s for retention tick ..."
sleep 100

kill -TERM "$PID" 2>/dev/null
sleep 5
kill -KILL "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null
echo "[retention-smoke] server stopped"
