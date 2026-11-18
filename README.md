# Bullseye

Bullseye Core is a standalone Linux host diagnostics agent. It reads host metrics and
Linux PSI from `/proc`, evaluates sustained CPU, memory, and IO pressure, and uses bounded
cgroup v2 CPU and memory collection to locate a likely workload source. Current state is
published as an atomically replaced JSON file.

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

Configuration is validated before the runtime starts. The metric window must be at least the
longest CPU, memory, or IO escalation/recovery duration plus twice the sampling interval:

```text
window.duration >= longest diagnostic duration + 2 * sampling.interval
```

The extra interval preserves an observed sample before the duration boundary even when sampling
does not align with it. For example, a 30-second recovery duration with 5-second sampling requires
a window of at least 40 seconds; a 10-second window is rejected. The bundled production and
Docker profiles already satisfy this requirement.

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
  "attributionConfidence": "MEDIUM",
  "attributionStatus": "IDENTIFIED",
  "attributionScore": 30.0,
  "attributionCollectedAt": 1788526800000,
  "attributionCoverage": {
    "complete": true,
    "collectedGroups": 3,
    "failedGroups": 0,
    "discoveryFailed": false,
    "discoveryLimited": false
  },
  "attributionEvidence": [
    {"metric": "memory.current", "value": 2147483648.0, "unit": "bytes"},
    {"metric": "memory.high", "value": 3221225472.0, "unit": "bytes"},
    {"metric": "limit.high.usage", "value": 66.66666666666666, "unit": "%"},
    {"metric": "memory.max", "value": 4294967296.0, "unit": "bytes"},
    {"metric": "limit.max.usage", "value": 50.0, "unit": "%"},
    {"metric": "host.share", "value": 12.5, "unit": "%"},
    {"metric": "observation.duration", "value": 5000.0, "unit": "ms"},
    {"metric": "growth.bytes", "value": 1073741824.0, "unit": "bytes"},
    {"metric": "growth.bytes-per-second", "value": 214748364.8, "unit": "bytes/s"},
    {"metric": "events.high.delta", "value": 2.0, "unit": "count"},
    {"metric": "events.max.delta", "value": 1.0, "unit": "count"},
    {"metric": "events.oom.delta", "value": 0.0, "unit": "count"},
    {"metric": "events.oom_kill.delta", "value": 0.0, "unit": "count"},
    {"metric": "psi.some.avg10", "value": 50.0, "unit": "%"},
    {"metric": "psi.full.avg10", "value": 2.0, "unit": "%"},
    {"metric": "score.usage", "value": 8.125, "unit": "points"},
    {"metric": "score.growth", "value": 21.875, "unit": "points"},
    {"metric": "score.pressure", "value": 0.0, "unit": "points"},
    {"metric": "score.events", "value": 0.0, "unit": "points"}
  ],
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
      "metrics": [
        {"metric": "HOST_CPU_USAGE", "value": 15.2, "unit": "%"},
        {"metric": "HOST_CPU_LOGICAL_COUNT", "value": 8.0, "unit": "count"}
      ]
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
- `FRESH`: required inputs and previously observed optional inputs are current.
- `STALE`: a tracked input expired, or required inputs are still missing after startup grace.

Both startup grace and sample freshness use twice `bullseye.sampling.interval`. CPU requires
usage, memory requires usage plus PSI some, and IO requires PSI some. CPU PSI and memory/IO PSI
full are optional until first observed; if subsequently lost, their resource becomes stale and
its diagnostic rule retains the last decision. Loss of that input cannot remove a recovery
condition or increase severity by removing pressure corroboration. A host that has never supplied
the optional PSI input continues to use the existing fallback behavior. Other resources continue
to evaluate their own fresh inputs independently.
The host logical CPU count is also tracked when available. It is required for CPU attribution,
but not for the host CPU pressure rule. Its source is the `cpuN` rows in the same `/proc/stat`
read as CPU usage, rather than the JVM's potentially quota-limited processor count.
Changes to that count or rollback of aggregate CPU ticks skip one host CPU usage sample to
establish a new baseline; CPU count, memory, and load metrics continue to be published.
Aggregate status is stale if any configured resource is stale, otherwise initializing if any
resource is initializing. `lastSampleAt` is the oldest latest timestamp across that resource's
tracked inputs, not the most recent sample of any metric. It is null until all required inputs
are available. A fresh CPU sample therefore cannot conceal missing memory or IO observations.

Collection outages preserve the last diagnosis instead of reporting a false recovery. Memory
attribution is not refreshed while its required host usage/PSI inputs or a previously observed
host PSI full input are expired. CPU attribution requires fresh host usage and CPU count and
also pauses when previously observed host CPU PSI expires. It discards its CPU measurement
baseline while retaining the last explanation until a new attempt. A resumed sample can restore
observation freshness immediately, but each applicable recovery condition still needs its full
continuous observation period. Samples before a collection gap cannot complete that period.

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
- CPU and memory trend rules still require a rise greater than the configured HIGH minimum;
  a flat high load alone does not satisfy that trend condition. Trend calculation uses the
  shortest trailing qualifying sample run that covers the configured HIGH duration, anchored
  to the newest observed sample. It does not reuse the entire rolling window's rise. Without
  interpolating samples, this run may exceed the requested duration by up to one allowed sample
  gap. Trend logs and their `duration` evidence report that actual interval, including fractional
  seconds such as `10.127s`; non-trend sustained logs retain the configured minimum duration.
- The highest active resource severity becomes host severity.
- CPU and memory attribution run on `HIGH` entry, then every
  `bullseye.cgroup.attribution.refresh-interval` (default `30s`) while HIGH persists.
  Failed/empty attempts retry on the same interval. `CRITICAL` rechecks on every fresh evaluation.
  CPU needs two snapshots to measure usage: `INSUFFICIENT_DATA` retries on the next fresh host
  sample instead of waiting for the refresh interval. Repeated evaluations of the same host CPU
  sample do not collect another CPU snapshot.
  Actual execution is aligned to diagnostic evaluations, so shorter settings do not increase
  the host sampling rate. Stale host inputs defer attempts without advancing the refresh timer.
- Recovery below HIGH clears that resource's attribution, previous cgroup samples, and refresh
  timer. CPU and memory histories/timers are independent; both remain active while their own
  severity is HIGH/CRITICAL, even if the other resource is selected as primary.
- cgroup discovery is cached and bounded. No background cgroup sampling executor is used.
- Memory attribution ranks host memory share and growth rate. Memory limits, PSI, and events
  explain the selected workload's condition without increasing its contribution score. CPU
  attribution ranks actual CPU consumption relative to host capacity. Similar candidates are
  retained with `LOW` confidence rather than overstating certainty.
- Unsupported, denied, empty, and insufficient-data outcomes are represented explicitly.

## Attribution evidence

`attributionEvidence` explains the selected workload; top-level `evidence` explains host severity.
`attributionCollectedAt` is the last attribution collection/attempt time, which may be older than
the heartbeat's `evaluatedAt` while HIGH reuses its cached result. Before any attempt, coverage
and collection time are null and attribution evidence is empty. Failed attempts still publish
their coverage and collection time, with no selected-workload evidence. The JSON fields and
source-announcement format are shared by CPU and memory attribution.

### Memory evidence

Memory attribution separates host consumption from pressure experienced inside a cgroup.
Only accounted memory usage and positive growth contribute to `attributionScore` (0 to 100):

```text
hostShare = memory.current / host memory total
growthSharePerSecond = growth.bytes-per-second / host memory total
score.usage = 65 * clamp(hostShare, 0, 1)
score.growth = 35 * clamp(growthSharePerSecond / 0.02, 0, 1)
attributionScore = score.usage + score.growth
```

| Component | Maximum | Saturation point |
| --- | ---: | --- |
| `score.usage` | 65 | Workload uses 100% of host memory; linear below that point |
| `score.growth` | 35 | Positive growth of 2% of host memory per second |
| `score.pressure` | 0 | Compatibility field; PSI does not contribute points |
| `score.events` | 0 | Compatibility field; memory events do not contribute points |

The four score keys remain present, and their sum still equals `attributionScore`. A stable
workload using 12 GiB on a 16 GiB host scores 48.75. A stable 64 MiB workload scores about 0.254,
even if its own tight limit causes heavy PSI or repeated limit events. A large consumer can
still be selected while it is also limited; limits neither increase nor reduce its score.
The shared `bullseye.cgroup.attribution.minimum-score` default of 5 requires about 7.69% of
host memory when there is no positive growth. Growth can qualify a smaller footprint. A zero
score never identifies a workload, including when the configured minimum is zero.

Growth uses byte delta divided by elapsed observation time. The same growth rate therefore
receives the same growth score at different refresh intervals. Negative growth remains visible
but contributes zero growth points. Event deltas report counts since the previous comparable
snapshot; `observation.duration` makes that interval explicit. Reset event counters contribute
zero delta. Without an earlier timestamp for the same workload, growth/event deltas and the
interval are omitted, and growth contributes zero points. Current usage can still qualify a
workload on its first snapshot.

| Supporting evidence | Meaning |
| --- | --- |
| `memory.high`, `memory.max` | Finite limits configured on the selected cgroup, in bytes |
| `limit.high.usage`, `limit.max.usage` | Current usage divided by that positive limit, as a percentage |
| `psi.some.avg10`, `psi.full.avg10` | Available cgroup memory PSI averages over ten seconds |
| `events.high.delta`, `events.max.delta`, `events.oom.delta`, `events.oom_kill.delta` | Observed event counter increases |

Missing or unlimited limits are omitted. A zero limit remains visible in bytes, but its usage
percentage is omitted to avoid division by zero. Limit percentages may exceed 100%; they
describe the currently configured local values, not effective capacity after ancestor limits.
Unavailable PSI is also omitted. These measurements do not classify the cause of pressure.

`memory.high` can force reclaim within a cgroup, while `memory.max` can trigger a cgroup OOM;
`oom_kill` can count a process killed by any OOM killer. Their presence does not establish a
contribution to host memory pressure. See the [kernel memory controller documentation](https://docs.kernel.org/admin-guide/cgroup-v2.html#memory-interface-files).
`memory.current` also includes memory that may be reclaimable, such as page cache. The ranking
is a consumption-based diagnostic heuristic, not a measurement of unreclaimable working set.

### CPU evidence

CPU usage is calculated from two `cpu.stat` snapshots with increasing collection timestamps:

```text
cpu.usage.cores = delta usage_usec / elapsed microseconds
host.share = cpu.usage.cores / host.cpu.count * 100
score.usage = min(100, host.share)
attributionScore = score.usage
```

For example, using two cores on an eight-core host contributes 25% of host CPU capacity and
scores 25 points. The shared `bullseye.cgroup.attribution.minimum-score` default of 5 therefore
requires at least 5% of host capacity for CPU attribution. `cpu.pressure`, local quota usage,
and throttling are supporting evidence only; they do not increase the score. A workload that
exhausts a small CPU quota is not automatically the source of host CPU saturation.

| Evidence | Meaning |
| --- | --- |
| `cpu.usage.cores`, `host.cpu.count`, `host.share` | Used cores, host logical CPUs, and host capacity share |
| `usage.delta`, `user.delta`, `system.delta` | CPU time consumed during the observation interval, in microseconds |
| `observation.duration` | Time between the two cgroup snapshots, in milliseconds |
| `quota.cores`, `quota.usage` | Local `cpu.max` quota/period and usage relative to that quota |
| `throttling.periods.delta`, `throttling.throttled-periods.delta` | Scheduling period and throttled period counter deltas |
| `throttling.duration.delta` | Reported throttled time delta, in microseconds |
| `psi.some.avg10` | Current cgroup CPU PSI some average over ten seconds |
| `score.usage` | CPU contribution score |
| `candidates.measured`, `candidates.unmeasured` | Collected groups with and without a usable earlier CPU snapshot |

First observations, CPU counter rollback, non-increasing timestamps, and observation intervals
longer than the refresh interval plus host sample freshness cannot supply a usage delta.
New groups, host collection gaps, CPU capacity changes, and recovery require a new baseline.
If there is no qualifying measured candidate and any group is unmeasured, attribution is
`INSUFFICIENT_DATA`; otherwise measurable usage below the threshold yields `NO_CANDIDATE`.
An identified winner is capped at LOW confidence while any collected group is unmeasured.

Missing optional CPU PSI, `cpu.max`, or throttling fields are omitted from evidence. Unlimited
quota omits quota evidence; a changed quota omits `quota.usage` for that interval. Quota evidence
describes the cgroup's own configured limit, not effective capacity after ancestor limits or
cpuset restrictions. Throttling deltas require both endpoints and monotonic counters, and are
not presented as host CPU utilization percentages.

### Confidence and collection coverage

Complete investigations retain the score/runner-up confidence policy: HIGH requires score >=45
and a winner/runner ratio >=max(2, configured dominance ratio); MEDIUM requires score >=20 and
the configured ratio; otherwise LOW. Tied scores use cgroup path order for stable selection.
Confidence expresses the strength of this heuristic ranking and collection coverage; it is
not a probability that the selected workload is the root cause.

`attributionCoverage` reports successful and failed group reads, discovery failure, and discovery
truncation at the depth/group bounds. Any incomplete investigation caps confidence at LOW.
Discovery flags remain attached to cached topology until successful rediscovery. A group read
failure invalidates that topology for the next attempt; otherwise discovery follows
`bullseye.cgroup.discovery-interval` (default `60s`). Newly created groups can therefore remain
unseen until the next discovery refresh, even when scoring already runs every 30s. Initial
cgroup access failures are retried on later attribution attempts.

Completeness describes the configured, visible cgroup tree. An isolated Docker cgroup namespace
does not reveal sibling containers; use a host-visible cgroup tree for cross-container attribution.
Both resources use discovered leaf candidates so parent and child counters are not ranked twice.
Score, evidence, collection-time and coverage-only updates refresh JSON without advancing the
diagnostic version or generating repeated source announcements. Workload/confidence/status changes
still produce transitions. Incomplete investigations include `coverage=PARTIAL` in source logs.

## Log style

Operational messages use short state announcements:

```text
[BULLSEYE] Bullseye diagnostic system activated.
[BULLSEYE] Host telemetry online.
[BULLSEYE] cgroup v2 detected.
[BULLSEYE] Memory pressure detected. usage=82.4% psi.some=13.1% sustained=10s previous=NORMAL current=ELEVATED resource=HOST_MEMORY
[BULLSEYE] Critical memory pressure detected. usage=88.1% psi.some=24.3% rise=+6.1pp/10s previous=ELEVATED current=HIGH resource=HOST_MEMORY
[BULLSEYE] Pressure source identified. resource=HOST_MEMORY workload=order-api.service confidence=MEDIUM score=30.0
[BULLSEYE] Pressure source identified. resource=HOST_CPU workload=worker.service confidence=HIGH score=60.0
```

## Real Linux validation

Before release, run the agent on a disposable Linux VM or server and verify CPU, memory, and IO
stress transitions, cgroup attribution, recovery, SIGTERM shutdown, and idle/stress overhead. Do
not run stress tools on production hosts. Docker Desktop smoke results alone do not complete this
check.
