# Bullseye

Bullseye Core is a standalone Linux host diagnostics agent. It reads host metrics and
Linux PSI from `/proc`, evaluates sustained CPU, memory, and IO pressure, and uses bounded
cgroup v2 sampling to locate a likely workload source. Current state is published as an
atomically replaced JSON file.

Bullseye observes and publishes state. It does not throttle traffic, modify application
behavior, or depend on an external database.

## Modules

- `bullseye-common`: metric, diagnostic, and workload domain models
- `bullseye-core`: Linux collectors, rolling window, diagnostics, state publication, runtime
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

The container reads the Linux `/proc` and cgroup filesystems supplied by Docker. The state
snapshot is bind mounted to `build/docker-state/state.json` on the host. Docker Desktop is useful
for a smoke test, but it is not a substitute for the real Linux host validation checklist below.

## State output

```json
{
  "host": "was-01",
  "application": "host",
  "severity": "HIGH",
  "resource": "HOST_MEMORY",
  "state": "SATURATION_RISK",
  "primaryWorkload": "order-api.service",
  "workloadType": "SYSTEMD_SERVICE",
  "cgroupPath": "/system.slice/order-api.service",
  "attributionConfidence": "HIGH",
  "evidence": [
    {"metric": "usage", "value": 88.1, "unit": "%"},
    {"metric": "psi.some.avg10", "value": 24.3, "unit": "%"}
  ],
  "since": 1788526800000,
  "version": 12
}
```

State is written to a temporary sibling file, flushed, and atomically moved over the current
snapshot. The version changes only when severity, primary resource, resource state, or primary
workload changes. Publisher, PSI, and individual cgroup failures are isolated from host telemetry.

## Diagnostic policy

- CPU keeps the usage-based hysteresis policy and uses PSI as corroborating evidence when present.
- Memory requires both high usage and memory PSI; high page-cache usage alone is observation only.
- IO is based on sustained PSI stalls rather than throughput.
- The highest active resource severity becomes host severity. Similar attribution candidates remain
  `UNKNOWN` instead of forcing a cause.
- cgroup discovery is cached and bounded. Sampling uses the cached targets on a separate executor.

## Log style

Operational messages use short state announcements:

```text
[BULLSEYE] Bullseye diagnostic system activated.
[BULLSEYE] Host telemetry online.
[BULLSEYE] cgroup v2 detected.
[BULLSEYE] Memory pressure detected. usage=82.4% psi.some=13.1% sustained=10s previous=NORMAL current=ELEVATED resource=HOST_MEMORY
[BULLSEYE] Warning. Memory saturation risk detected. usage=88.1% psi.some=24.3% rise=+6.1pp/10s previous=ELEVATED current=HIGH resource=HOST_MEMORY
[BULLSEYE] Pressure source identified. resource=HOST_MEMORY workload=order-api.service confidence=HIGH
```

## Real Linux validation

Before release, run the agent on a disposable Linux VM or server and verify CPU, memory, and IO
stress transitions, cgroup attribution, recovery, SIGTERM shutdown, and idle/stress overhead. Do
not run stress tools on production hosts. Docker Desktop smoke results alone do not complete this
check.
