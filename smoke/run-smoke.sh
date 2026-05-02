#!/usr/bin/env bash
# Run a Folia/Luminol server with CoreProtect for ~60s, capture log, scan for issues.
set -uo pipefail
DIR="${1:-srv26}"
JAR="${2:-luminol-paperclip-26.1.2.local-SNAPSHOT.jar}"
JDK="${3:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
LABEL="${4:-26.1.2}"

cd "$(dirname "$0")/$DIR" || exit 2
LOG="$(pwd)/run.log"
: > "$LOG"

echo "[smoke] booting $LABEL with $JAR ..." | tee -a "$LOG"
"$JDK/bin/java" -Xmx2G -Xms1G -Dcom.mojang.eula.agree=true -jar "$JAR" --nogui >>"$LOG" 2>&1 &
PID=$!

WAIT=120
ELAPSED=0
DONE=0
while [ $ELAPSED -lt $WAIT ]; do
  if grep -qE 'Done \(' "$LOG" 2>/dev/null; then DONE=1; break; fi
  sleep 2
  ELAPSED=$((ELAPSED+2))
done

# Let it idle a bit
sleep 15

# Stop
echo "stop" > /tmp/_smoke_stop || true
kill -TERM "$PID" 2>/dev/null
sleep 5
kill -KILL "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null

echo "----- VERDICT ($LABEL) -----"
if [ "$DONE" -ne 1 ]; then
  echo "FAIL: server never reached 'Done (' within ${WAIT}s"
  tail -40 "$LOG"
  exit 1
fi

# Look for fatal errors
ERRORS=0
for pat in 'Could not load .*CoreProtect' 'Error occurred while enabling CoreProtect' 'CoreProtect.*has been disabled' 'Could not pass event.*CoreProtect' '\\[CoreProtect\\] [^E].*Failed to' 'CoreProtect is unsupported'; do
  if grep -qE "$pat" "$LOG"; then
    echo "FAIL: matched '$pat'"
    grep -E "$pat" "$LOG" | head -5
    ERRORS=$((ERRORS+1))
  fi
done

# Confirm enable + commands registered
if grep -qE '\[CoreProtect\] (Loading|Enabling)' "$LOG"; then
  echo "OK: CoreProtect Enabling line found"
else
  echo "FAIL: no Enabling line"
  ERRORS=$((ERRORS+1))
fi
if grep -qE 'CoreProtect (v[0-9]|version|enabled|loaded)' "$LOG"; then
  echo "OK: CoreProtect version line found"
fi

if [ "$ERRORS" -eq 0 ]; then
  echo "PASS: $LABEL smoke clean"
  exit 0
else
  echo "FAIL: $ERRORS issue(s) above"
  exit 1
fi
