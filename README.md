# Bullseye

Bullseye Core is a standalone Linux host diagnostics agent. It reads host metrics and
Linux PSI from `/proc`, evaluates sustained CPU, memory, and IO pressure, and uses bounded
cgroup v2 memory collection to locate a likely workload source. Current state is published as an
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
  "attributionStatus": "IDENTIFIED",
  "attributionScore": 91.0,
  "evidence": [
    {"metric": "usage", "value": 88.1, "unit": "%"},
    {"metric": "psi.some.avg10", "value": 24.3, "unit": "%"}
  ],
  "evaluatedAt": 1788526810000,
  "observationStatus": "FRESH",
  "observations": {
    "HOST_CPU": {
      "status": "FRESH",
      "lastSampleAt": 1788526810000,
      "metrics": [{"metric": "HOST_CPU_USAGE", "value": 15.2, "unit": "%"}]
    },
    "HOST_MEMORY": {
      "status": "FRESH",
      "lastSampleAt": 1788526810000,
      "metrics": [
        {"metric": "HOST_MEMORY_USAGE", "value": 88.1, "unit": "%"},
        {"metric": "HOST_MEMORY_PSI_SOME_AVG10", "value": 24.3, "unit": "%"}
      ]
    },
    "DISK_IO": {
      "status": "FRESH",
      "lastSampleAt": 1788526810000,
      "metrics": [{"metric": "HOST_IO_PSI_SOME_AVG10", "value": 0.0, "unit": "%"}]
    }
  },
  "since": 1788526800000,
  "version": 12
}
```

State is written to a temporary sibling file, flushed, and atomically moved over the current
snapshot. Every completed diagnostic evaluation refreshes the snapshot, even without a state
transition. The version and `since` change only when severity, primary resource, resource state,
primary workload, confidence, or attribution status changes. Heartbeats, score-only changes, and
observation-status changes do not increment the version or emit duplicate diagnostic transitions.

`evaluatedAt` records the latest completed evaluation. `observations` contains current telemetry
for each configured resource. Top-level `evidence` remains the basis of the diagnostic decision;
it can refer to an earlier decision while hysteresis retains that diagnosis. Use
`observations.metrics` for current values. Expired values are omitted from these metric lists.

Observation freshness is separate from pressure severity:

- `INITIALIZING`: required inputs have not all arrived within the startup grace period.
- `FRESH`: required inputs and previously observed optional PSI inputs are current.
- `STALE`: a tracked input expired, or required inputs are still missing after startup grace.

Both startup grace and sample freshness use twice `bullseye.sampling.interval`. CPU requires
usage, memory requires usage plus PSI some, and IO requires PSI some. CPU PSI and memory/IO PSI
full are optional until first observed; if subsequently lost, their resource becomes stale.
Aggregate status is stale if any configured resource is stale, otherwise initializing if any
resource is initializing. `lastSampleAt` is the oldest latest timestamp across that resource's
tracked inputs, not the most recent sample of any metric. It is null until all required inputs
are available. A fresh CPU sample therefore cannot conceal missing memory or IO observations.

Collection outages preserve the last diagnosis instead of reporting a false recovery. Memory
attribution is not refreshed while its required host usage/PSI inputs are expired. A resumed
sample can restore observation freshness immediately, but escalation and recovery still need
their configured continuous observation durations.

Consumers must also check the age of `evaluatedAt` against their heartbeat timeout (for example,
a few sampling intervals). A stopped or blocked agent cannot rewrite its last `FRESH` file to
`STALE`. The timestamp is an evaluation heartbeat, not a successful disk-write timestamp.

The publisher retains only the newest pending snapshot, plus any write already in progress.
Failed writes retry every second without requiring a new transition; newer snapshots replace
older pending values. Failure/restoration logs are emitted once per outage. Shutdown attempts
to flush pending state within its timeout, then interrupts the writer. Publisher, PSI, and
individual cgroup failures are isolated from host telemetry.

## Diagnostic policy

- CPU keeps the usage-based hysteresis policy and uses PSI as corroborating evidence when present.
- Memory requires both high usage and memory PSI; high page-cache usage alone is observation only.
- IO is based on sustained PSI stalls rather than throughput.
- A sample gap greater than twice the sampling interval breaks a sustained run. Duration is
  measured between observed samples; time after the newest sample does not complete the run.
- The highest active resource severity becomes host severity.
- Memory attribution runs when memory enters `HIGH` or remains `CRITICAL`; repeated `HIGH`
  evaluations reuse the previous result.
- cgroup discovery is cached and bounded. No background cgroup sampling executor is used.
- Attribution combines host share, recent growth, memory PSI, and memory event deltas. Similar
  candidates are retained with `LOW` confidence rather than overstating certainty.
- Unsupported, denied, empty, and insufficient-data outcomes are represented explicitly.

## Log style

Operational messages use short state announcements:

```text
[BULLSEYE] Bullseye diagnostic system activated.
[BULLSEYE] Host telemetry online.
[BULLSEYE] cgroup v2 detected.
[BULLSEYE] Memory pressure detected. usage=82.4% psi.some=13.1% sustained=10s previous=NORMAL current=ELEVATED resource=HOST_MEMORY
[BULLSEYE] Critical memory pressure detected. usage=88.1% psi.some=24.3% rise=+6.1pp/10s previous=ELEVATED current=HIGH resource=HOST_MEMORY
[BULLSEYE] Pressure source identified. resource=HOST_MEMORY workload=order-api.service confidence=HIGH score=91.0
```

## Real Linux validation

Before release, run the agent on a disposable Linux VM or server and verify CPU, memory, and IO
stress transitions, cgroup attribution, recovery, SIGTERM shutdown, and idle/stress overhead. Do
not run stress tools on production hosts. Docker Desktop smoke results alone do not complete this
check.
