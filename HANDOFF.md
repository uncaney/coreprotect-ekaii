# coreprotect-ekaii — autonomous build + CI port

Goal: build PlayPro/CoreProtect for both **MC 1.21.11** and **MC 26.1.2** on
`forgejo.ekaii.fr` (admin_ekaii owner `exo/coreprotect-ekaii`) with mirror to
`github.com/uncaney/coreprotect-ekaii`. Validate via local Folia smoke test.

## Layout

- `upstream/` — git clone of `github.com/PlayPro/CoreProtect` at HEAD (`d7aa696`,
  post-v23.2 May 2026). Two branches:
  - `master` — HEAD; targets paper-api `26.1.2.build.9-alpha`, JDK 25.
  - `mc1.21.11` — frozen at `2979af3` (CoreProtect Community Edition v23.1
    release, Dec 14 2025); targets paper-api `1.21.11-R0.1-SNAPSHOT`, JDK 21.
- `dist/` — locally-built artifacts:
  - `CoreProtect-23.1-mc1.21.11.jar` (1.9 MB shaded, Java 11 bytecode).
  - `CoreProtect-23.2-mc26.1.2.jar` (2.2 MB shaded, Java 11 bytecode).

## Build (local)

```bash
# 1.21.11 jar
cd upstream && git checkout mc1.21.11
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  PATH=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
  mvn -B -DskipTests clean verify
cp target/CoreProtect-23.1.jar ../dist/CoreProtect-23.1-mc1.21.11.jar

# 26.1.2 jar
cd upstream && git checkout master
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  PATH=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
  mvn -B -DskipTests clean verify
cp target/CoreProtect-23.2.jar ../dist/CoreProtect-23.2-mc26.1.2.jar
```

Both jars compile to **Java 11 bytecode** (`maven.compiler.target=11` in pom.xml)
so the resulting jars run on any JDK 11+ Folia/Paper instance — only the build
toolchain needs the matching JDK.

## CI (forgejo)

`.forgejo/workflows/build.yml` is a matrix that builds both targets on every
push to `master` or `mc1.21.11`. Container: `node:20-bookworm` (forgejo runner
needs node in PATH; alpine + JDK images don't have it). JDK installed via
`actions/setup-java@v4`. Artifacts uploaded with `actions/upload-artifact@v3`
(v4 is unsupported on forgejo).

Triggers: push to either branch, push of any `v*` tag (cuts a release with
both jars attached), or manual `workflow_dispatch`.

## Repos

- Forgejo (canonical, public): https://forgejo.ekaii.fr/exo/coreprotect-ekaii
- GitHub (mirror, public): https://github.com/uncaney/coreprotect-ekaii

Push tokens are at `~/.secrets/forgejo-ekaii.txt` (admin_ekaii) and
`~/.secrets/github-uncaney.txt` (uncaney PAT).

## Purge / log retention answer

Question: does the latest push add a feature to choose how long logs are kept?

**Answer: no.** The Apr 23 commit `7caa617` "Added argument validation to purge
command (fixes #848)" adds input validation to the *existing* `/co purge`
command — it now rejects unknown args (e.g. `r:30` with a typo) instead of
silently ignoring them. It does NOT add an auto-retention config option.

Retention has always been entirely command-driven:
- `src/main/resources/plugin.yml` declares the `purge` permission and the
  `coreprotect.purge` perm node.
- `src/main/java/net/coreprotect/command/PurgeCommand.java` parses
  `/co purge t:<time>` and runs a one-shot delete of rows older than `<time>`.
- There is **no** `purge-time` / `auto-purge` key in CoreProtect's default
  config (verified — `src/main/resources/` only contains `plugin.yml`; `Config.java`
  has no purge-time field).

To get automatic retention, you have to schedule `/co purge t:<time>` yourself
via cron / a server scheduler / `/co purge t:30d #optimize` in a server start
script.

The Apr 23 commit's diff confirms this — it's purely a `findUnsupportedPurgeArgument()`
helper that walks the args list and returns the first unrecognised token, then
the runCommand body sends `INVALID_PARAMETER` if any such token exists. No new
config keys; no new persistent state.
