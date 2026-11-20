# Bullseye

Bullseye Core is a standalone Linux host diagnostics agent. It reads host metrics and
Linux PSI from `/proc`, evaluates sustained CPU, memory, and IO pressure, and uses bounded
cgroup v2 CPU, memory, and IO collection to locate a likely workload source. Current state is
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
`bin`, `lib`, and `config` directories alongside the `LICENSE` file.

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

The top-level fields summarize the primary diagnosis. `resources` publishes every configured
resource's diagnosis and workload attribution, including resources that are not primary. The
example below abbreviates the per-resource evidence and attribution fields for readability.

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
    {"metric": "score.events", "value": 0.0, "unit": "points"},
    {"metric": "confidence.observations", "value": 3.0, "unit": "count"},
    {"metric": "confidence.duration", "value": 10.0, "unit": "s"},
    {"metric": "confidence.margin", "value": 20.0, "unit": "points"},
    {"metric": "confidence.runner-score", "value": 10.0, "unit": "points"},
    {"metric": "confidence.confirmations-remaining", "value": 0.0, "unit": "count"}
  ],
  "evidence": [
    {"metric": "usage", "value": 88.1, "unit": "%"},
    {"metric": "psi.some.avg10", "value": 24.3, "unit": "%"}
  ],
  "evaluatedAt": 1788526810000,
  "wallClockAt": 1788526810000,
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
  "resources": {
    "HOST_CPU": {
      "severity": "NORMAL",
      "state": "NORMAL",
      "reason": "CPU condition nominal.",
      "since": 1788526700000,
      "evidence": [],
      "primaryWorkload": "UNKNOWN",
      "attributionStatus": "NOT_ATTEMPTED"
    },
    "HOST_MEMORY": {
      "severity": "HIGH",
      "state": "SATURATION_RISK",
      "reason": "Critical memory pressure detected.",
      "since": 1788526800000,
      "evidence": [{"metric": "usage", "value": 88.1, "unit": "%"}],
      "primaryWorkload": "order-api.service",
      "attributionStatus": "IDENTIFIED",
      "attributionConfidence": "MEDIUM",
      "attributionScore": 30.0
    },
    "DISK_IO": {
      "severity": "NORMAL",
      "state": "NORMAL",
      "reason": "IO condition nominal.",
      "since": 1788526700000,
      "evidence": [],
      "primaryWorkload": "UNKNOWN",
      "attributionStatus": "NOT_ATTEMPTED"
    }
  },
  "since": 1788526800000,
  "version": 12
}
```

State is written to a temporary sibling file, flushed, and atomically moved over the current
snapshot. Every completed diagnostic evaluation refreshes the snapshot, even without a state
transition. `version` advances once per evaluation when the primary result or any resource's
severity, state, workload, attribution confidence, or attribution status changes. A secondary
memory or IO transition therefore advances the version even while CPU remains primary.
Top-level `since` changes only with the primary result; each `resources.<resource>.since` changes
only with that resource's diagnostic result, including source/confidence/status changes. Merely
selecting a different primary does not reset the underlying resources' `since` values.
Heartbeats, evidence/reason text, scores, collection timestamps, coverage, and observation-status
changes alone refresh JSON without advancing the version or emitting diagnostic transitions.

Every resource entry includes `severity`, `state`, `reason`, `since`, and diagnostic `evidence`,
plus the same flat attribution fields as the top-level summary: `primaryWorkload`, `workloadType`,
`cgroupPath`, `attributionConfidence`, `attributionStatus`, `attributionScore`,
`attributionCollectedAt`, `attributionCoverage`, and `attributionEvidence`. Here `primaryWorkload`
means the leading candidate for that resource. CPU, memory, and IO can identify different
workloads in the same snapshot. The summary continues to use the existing primary-selection
and hysteresis policy.

Read each diagnosis with the corresponding `observations.<resource>.status` and `lastSampleAt`.
A retained diagnosis can be stale; a NORMAL entry during startup is not evidence that telemetry
has arrived. Before the first evaluation, `resources` is empty. The first evaluation fills
configured resource entries; materializing unchanged NORMAL entries does not advance the version
or announce a recovery. Recovery clears attribution only for the recovered resource.

`evaluatedAt` is the time at which the latest completed evaluation captured its inputs.
It advances with monotonic elapsed time from the UTC epoch timestamp sampled at process startup.
`since`, `lastSampleAt`, and `attributionCollectedAt` use the same timeline. System clock
corrections during the run leave pressure durations, freshness, and attribution intervals
unaffected. A restart establishes a new timeline.
Each collection round adds its successful samples to the rolling window together. Diagnostics,
host inputs for attribution, and `observations` all use the same fixed view of samples at or
before `evaluatedAt`. Top-level `evidence` remains the basis of the diagnostic decision;
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
establish a new baseline; CPU count and memory metrics continue to be published.
Aggregate status is stale if any configured resource is stale, otherwise initializing if any
resource is initializing. `lastSampleAt` is the oldest latest timestamp across that resource's
tracked inputs, not the most recent sample of any metric. It is null until all required inputs
are available. A fresh CPU sample therefore cannot conceal missing memory or IO observations.

Collection outages preserve the last diagnosis instead of reporting a false recovery. Memory
attribution pauses while required host usage/PSI inputs or previously observed host PSI full
are expired. CPU attribution requires fresh host usage and CPU count and also pauses when
previously observed host CPU PSI expires. Both discard their confirmation history; any identified
source is retained with its original evidence and collection time, but confidence drops to LOW.
CPU also discards its measurement baseline. Fresh observations must confirm confidence again.
IO attribution requires fresh host PSI some and pauses if previously observed host PSI full
expires, clearing its measurement baseline. A resumed sample can restore
observation freshness immediately, but each applicable recovery condition still needs its full
continuous observation period. Samples before a collection gap cannot complete that period.

`wallClockAt` is the system UTC epoch timestamp captured with the evaluation inputs, in
milliseconds. It can move forward or backward when the system clock changes, and a publication
retry preserves the captured value. Use it to place an evaluation on the system clock.
A stopped or blocked agent leaves its last snapshot in place. Consumers polling the state file
can use their own monotonic timeout (for example, a few sampling intervals) to detect when
`evaluatedAt` stops advancing. Both timestamps describe the evaluation, independent of when
the file write completes.

The publisher retains only the newest pending snapshot, plus any write already in progress.
Failed writes retry every second without requiring a new transition; newer snapshots replace
older pending values. Failure/restoration logs are emitted once per outage. Shutdown attempts
to flush pending state within its timeout, then interrupts the writer. Publisher, PSI, and
individual cgroup failures are isolated from host telemetry.

Host CPU (`/proc/stat`) and memory (`/proc/meminfo`) collection are independent. A read or
parse failure omits only that resource's samples, which age through the existing freshness
policy. All metrics from a single proc file are accepted together. A CPU failure also clears
its tick baseline: the first successful read restores CPU count, and the next comparable read
restores usage. Memory collection continues during CPU failures, and CPU collection continues
during memory failures. Component interruption and restoration logs are emitted once per
outage, separately for CPU, memory, and failures of an entire collector.

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
- CPU, memory, and IO attribution run on `HIGH` entry, then every
  `bullseye.cgroup.attribution.refresh-interval` (default `30s`) while HIGH persists.
  Failed/empty attempts retry on the same interval. `CRITICAL` rechecks on every fresh evaluation.
  CPU and IO need two snapshots to measure activity: `INSUFFICIENT_DATA` retries on the next
  fresh host sample instead of waiting for the refresh interval. Repeated evaluations of the
  same host CPU usage or IO PSI some sample do not collect another snapshot for that resource.
  CPU and memory can make bounded extra confirmation attempts on new host usage samples;
  the confidence settings below control this burst without changing the regular refresh policy.
  Actual execution is aligned to diagnostic evaluations, so shorter settings do not increase
  the host sampling rate. Stale host inputs defer attempts without advancing the refresh timer.
- Recovery below HIGH clears that resource's attribution, previous cgroup samples, and refresh
  timer. All three histories/timers are independent and remain active while their own severity
  is HIGH/CRITICAL, even if another resource is selected as primary.
- cgroup discovery is cached and bounded. No background cgroup sampling executor is used.
  `CgroupMonitor` uses one collection loop and a typed `CollectionResult<T>` for CPU, memory,
  and IO. Discovery, failure isolation, coverage, and collection timestamps share the same
  implementation; each resource keeps its own parser and snapshot history. Rediscovery
  prunes disappeared groups from all three histories.
- Memory attribution ranks host memory share and growth rate. Memory limits, PSI, and events
  explain the selected workload's condition without increasing its contribution score. CPU
  attribution ranks actual CPU consumption relative to host capacity. Similar candidates are
  retained with `LOW` confidence rather than overstating certainty.
- IO compares observed byte and operation shares within each device. It selects a common
  dominant workload only when active devices agree; ambiguity yields `NO_CANDIDATE`.
- Unsupported, denied, empty, and insufficient-data outcomes are represented explicitly.

## Attribution evidence

`attributionEvidence` explains the selected workload; top-level `evidence` explains host severity.
`attributionCollectedAt` is the last attribution collection/attempt time, which may be older than
the heartbeat's `evaluatedAt` while HIGH reuses its cached result. Before any attempt, coverage
and collection time are null and attribution evidence is empty. Failed attempts still publish
their coverage and collection time, with no selected-workload evidence. The JSON fields and
source-announcement format are shared by CPU, memory, and IO attribution.

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
zero delta. Without an earlier timestamp for the same workload, or when the observation gap
exceeds the refresh interval plus host sample freshness, growth/event deltas and the interval
are omitted. A host memory capacity change also discards previous growth history. Current usage
can still select a workload on its first snapshot, initially at LOW confidence.

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

### IO evidence

Two `io.stat` snapshots provide read/write byte and operation deltas for each `MAJOR:MINOR`
device. Deltas are divided by elapsed seconds before comparing workloads on the same device.
Discard counters are not scored. The device score is calculated as follows:

```text
observed byte share = workload bytes/second / observed device bytes/second * 100
observed operation share = workload operations/second / observed device operations/second * 100
device score = (observed byte share + observed operation share) / 2
attributionScore = minimum device score of the common winning workload
```

When only one activity axis has a positive total, that axis supplies the entire device score.
A device with no measured read/write activity is inactive. On every active device the winner
must meet the shared minimum score and beat the runner-up by the configured dominance ratio.
Ties, insufficient dominance, and different winners across devices yield `NO_CANDIDATE`.
For example, 90% of bytes and 70% of operations on one device scores 80. Another device with
a different leading workload prevents selection, even if its absolute traffic is smaller.

IO confidence is always `LOW`: this identifies a workload leading the **observed IO activity**,
not a proven bottleneck. The score is neither disk utilization nor a share of host IO capacity.
Even a sole low-traffic group can score 100. Host PSI does not identify the stalled device, and
this implementation does not measure device capacity or latency. Conservatively withholding a
candidate across conflicting devices can therefore miss a real source.

Evidence is grouped under `device.<MAJOR:MINOR>.*`, with read/write deltas and rates, observed
byte/operation shares, and the device score. It also includes the observation duration, measured
and unmeasured candidate/device counts, and available cgroup PSI and local `io.max` limits.
PSI and limits add no points. A stalled workload with no measured IO is not selected simply
because it waits; a workload with a finite limit is still eligible based on its activity.
Local limit usage is included only when the limit is unchanged across the interval. It excludes
ancestor limits. Missing `io.max` is distinguished from an available file with no finite limits.

First observations, new/reset device counters, non-increasing timestamps, and intervals longer
than the refresh interval plus host sample freshness cannot provide a device delta. Other valid
devices can still be measured, with missing comparisons reported as unmeasured. If no candidate
can be measured and comparisons are missing, the result is `INSUFFICIENT_DATA`; known zero
activity yields `NO_CANDIDATE`. Host collection gaps and recovery require a new baseline.
An empty `io.stat` is valid idle data. Device rows may omit all four read/write counters when
zero; partial counter sets, malformed files, and denied reads are collection failures. Optional
controller extension fields are tolerated. Missing `io.pressure` or `io.max` is supported.

Kernel accounting also depends on filesystem writeback support and may assign shared-file IO
to a different cgroup than the writer. See the [cgroup IO/writeback documentation](https://docs.kernel.org/admin-guide/cgroup-v2.html#io)
and [PSI semantics](https://docs.kernel.org/accounting/psi.html).

### Confidence and collection coverage

CPU/memory candidates initially report LOW confidence. The score thresholds still determine
the eligible level: HIGH requires score >=45 and winner/runner ratio >=max(2, configured
dominance ratio); MEDIUM requires score >=20 and the configured ratio. By default, promotion
requires the same winner and eligible level across at least three distinct, complete observations
spanning ten seconds, with a winner/runner score margin of at least five points. Tied scores use
cgroup path order for stable selection. IO remains LOW regardless of these settings.

The following keys use the `bullseye.cgroup.attribution.confidence.` prefix:

| Setting | Default | Meaning |
| --- | ---: | --- |
| `minimum-observations` | `3` | Consecutive qualifying observations; minimum 2 |
| `minimum-duration` | `10s` | Time from the first qualifying observation to the latest; minimum 1ms |
| `maximum-confirmation-collections` | `3` | Extra collection attempts per confirmation burst; 0 uses regular attempts only |
| `minimum-margin` | `5` | Required winner/runner score difference, from 0 to 100 points |

Extra attempts stop when confidence is confirmed, collection quality fails, or the budget is
exhausted. Regular HIGH refreshes and CRITICAL checks can still complete confirmation later.
Duplicate collection timestamps do not add observations or duration, but consume an extra
attempt when confirmation is pending. Winner or eligible-level changes restart the observation
sequence without replenishing an active burst's budget.

Partial/failed collections, missing candidates, backward timestamps, CPU usage counter or memory
event resets, host capacity changes, and gaps longer than the refresh interval plus host sample
freshness break confirmation.
CPU additionally requires every collected candidate to have a usable delta, and successive winner
intervals must join at the previous observation; baseline-only snapshots do not count. Memory may
confirm stable usage without growth.
Confirmed confidence drops immediately when these conditions no longer hold; a new qualifying
sequence is required. Confidence describes ranking strength and observed consistency, not a
probability that the selected workload is the root cause.

CPU/memory attribution evidence includes `confidence.observations` (count), `confidence.duration`
(seconds), `confidence.margin` and `confidence.runner-score` (points), and
`confidence.confirmations-remaining` (count). These fields explain the current confirmation state;
as with other attribution evidence, retained stale results describe the last collection.

`attributionCoverage` reports successful and failed group reads, discovery failure, and discovery
truncation at the depth/group bounds. Any incomplete investigation caps confidence at LOW.
Discovery flags remain attached to cached topology until successful rediscovery. A group read
failure invalidates that topology for the next attempt; otherwise discovery follows
`bullseye.cgroup.discovery-interval` (default `60s`). Newly created groups can therefore remain
unseen until the next discovery refresh, even when scoring already runs every 30s. Initial
cgroup access failures are retried on later attribution attempts.

Completeness describes the configured, visible cgroup tree. An isolated Docker cgroup namespace
does not reveal sibling containers; use a host-visible cgroup tree for cross-container attribution.
All three resources use discovered leaf candidates so parent and child counters are not ranked twice.
Score, evidence, collection-time and coverage-only updates refresh JSON without advancing the
diagnostic version or generating repeated source announcements. Workload/confidence/status changes
still produce transitions. Incomplete investigations include `coverage=PARTIAL` in source logs.

## Log style

Operational messages use short state announcements:

Each configured resource announces its own state and source changes, even when another resource
remains primary. `previous` and `current` refer to that resource's severity. A source-only change
announces the source without repeating the pressure message. If the primary selection changes
without a state announcement for the newly selected resource, one `Primary resource reassigned.`
message records the selection; it does not repeat an already identified source.

```text
[BULLSEYE] Bullseye diagnostic system activated.
[BULLSEYE] Host telemetry online.
[BULLSEYE] cgroup v2 detected.
[BULLSEYE] Memory pressure detected. usage=82.4% psi.some=13.1% sustained=10s previous=NORMAL current=ELEVATED resource=HOST_MEMORY
[BULLSEYE] Critical memory pressure detected. usage=88.1% psi.some=24.3% rise=+6.1pp/10s previous=ELEVATED current=HIGH resource=HOST_MEMORY
[BULLSEYE] Pressure source identified. resource=HOST_MEMORY workload=order-api.service confidence=MEDIUM score=30.0
[BULLSEYE] Pressure source identified. resource=HOST_CPU workload=worker.service confidence=HIGH score=60.0
[BULLSEYE] Pressure source identified. resource=DISK_IO workload=writer.service confidence=LOW score=80.0
[BULLSEYE] Primary resource reassigned. previous=HOST_CPU current=HOST_MEMORY severity=HIGH
```

## Real Linux validation

Before release, run the agent on a disposable Linux VM or server and verify CPU, memory, and IO
stress transitions, cgroup attribution, recovery, SIGTERM shutdown, and idle/stress overhead. Do
not run stress tools on production hosts. Docker Desktop smoke results alone do not complete this
check.

## License

Bullseye Core is licensed under the [MIT License](LICENSE).
