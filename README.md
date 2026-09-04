# Bullseye

Bullseye Core is a standalone Linux host diagnostics agent. It reads host metrics from
`/proc`, keeps a bounded in-memory time window, evaluates sustained CPU pressure, and
publishes the current state as an atomically replaced JSON file.

Bullseye observes and publishes state. It does not throttle traffic, modify application
behavior, or depend on an external database.

## Modules

- `bullseye-common`: metrics and diagnostic domain models
- `bullseye-core`: collection, rolling window, diagnostics, state, publication, runtime
- `bullseye-native`: native runtime abstraction and Java fallback

## Requirements

- Linux runtime
- JDK 25 for development; the Gradle toolchain selects it automatically

The installed application distribution does not require Gradle. A dedicated runtime image
can be added with `jlink` without changing the domain or runtime boundaries.

## Build and test

```shell
./gradlew test
./gradlew :bullseye-core:installDist
```

The generated distribution is under `bullseye-core/build/install/bullseye-core` and includes
`bin`, `lib`, and `config` directories.

## Run

From the installed distribution root:

```shell
./bin/bullseye-core
```

Pass a properties file as the first argument to use a different configuration:

```shell
./bin/bullseye-core /etc/bullseye/bullseye.properties
```

Defaults are defined in [`config/bullseye.properties`](config/bullseye.properties). The
default state output is `/run/bullseye/state.json`.

## Docker smoke test

The Docker configuration uses a one-second sampling interval and deliberately sensitive CPU
thresholds so a state transition can be observed quickly. It is not a production configuration.

```shell
docker compose up --build -d
docker compose logs -f bullseye
cat build/docker-state/state.json
docker compose down
```

The container reads the Linux `/proc` filesystem supplied by Docker. The state snapshot is bind
mounted to `build/docker-state/state.json` on the host.

## State output

```json
{
  "host": "was-01",
  "application": "host",
  "severity": "HIGH",
  "resource": "HOST_CPU",
  "state": "SATURATION_RISK",
  "since": 1788526800000,
  "version": 182
}
```

State is written to a temporary sibling file, flushed, and atomically moved over the current
snapshot. Publisher failures are isolated from metric collection and diagnostics.

## Log style

Operational messages use short state announcements:

```text
[BULLSEYE] Bullseye diagnostic system activated.
[BULLSEYE] Host telemetry online.
[BULLSEYE] CPU pressure detected. usage=74.2% sustained=10s previous=NORMAL current=ELEVATED resource=HOST_CPU
[BULLSEYE] Warning. CPU saturation risk detected. usage=84.1% rise=+5.2pp/10s previous=ELEVATED current=HIGH resource=HOST_CPU
[BULLSEYE] CPU pressure cleared. usage=60.3% sustained=30s previous=HIGH current=NORMAL resource=HOST_CPU
```
