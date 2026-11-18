package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.linux.cgroup.CgroupIoSnapshot;
import io.bullseye.core.linux.cgroup.CgroupIoSnapshot.DeviceLimit;
import io.bullseye.core.linux.cgroup.CgroupIoSnapshot.DeviceStats;
import io.bullseye.core.linux.cgroup.CgroupMonitor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

public final class IoPressureAttributor {

    private final Supplier<CgroupMonitor.IoCollectionResult> collections;
    private final Runnable historyReset;
    private final double minimumScore;
    private final double dominanceRatio;
    private final long maximumObservationGapMillis;

    public IoPressureAttributor(
            CgroupMonitor monitor,
            double minimumScore,
            double dominanceRatio,
            Duration maximumObservationGap) {
        this(
                Objects.requireNonNull(monitor, "monitor")::collectIo,
                monitor::clearIoHistory,
                minimumScore,
                dominanceRatio,
                maximumObservationGap);
    }

    IoPressureAttributor(
            Supplier<CgroupMonitor.IoCollectionResult> collections,
            double minimumScore,
            double dominanceRatio,
            Duration maximumObservationGap) {
        this(collections, () -> {}, minimumScore, dominanceRatio, maximumObservationGap);
    }

    private IoPressureAttributor(
            Supplier<CgroupMonitor.IoCollectionResult> collections,
            Runnable historyReset,
            double minimumScore,
            double dominanceRatio,
            Duration maximumObservationGap) {
        this.collections = Objects.requireNonNull(collections, "collections");
        this.historyReset = Objects.requireNonNull(historyReset, "historyReset");
        if (!Double.isFinite(minimumScore) || minimumScore < 0) {
            throw new IllegalArgumentException("minimumScore must be finite and non-negative");
        }
        if (!Double.isFinite(dominanceRatio) || dominanceRatio <= 1) {
            throw new IllegalArgumentException("dominanceRatio must be greater than 1");
        }
        Objects.requireNonNull(maximumObservationGap, "maximumObservationGap");
        maximumObservationGapMillis = maximumObservationGap.toMillis();
        if (maximumObservationGapMillis < 1) {
            throw new IllegalArgumentException("maximumObservationGap must be at least 1ms");
        }
        this.minimumScore = minimumScore;
        this.dominanceRatio = dominanceRatio;
    }

    public WorkloadAttribution attribute() {
        CgroupMonitor.IoCollectionResult collection = collections.get();
        if (collection.status() != CgroupMonitor.Status.AVAILABLE) {
            reset();
            WorkloadAttribution.Status status =
                    collection.status() == CgroupMonitor.Status.ACCESS_DENIED
                            ? WorkloadAttribution.Status.ACCESS_DENIED
                            : WorkloadAttribution.Status.CGROUP_V2_NOT_AVAILABLE;
            return unavailable(status, collection);
        }

        Map<String, List<DeviceMeasurement>> devices = new TreeMap<>();
        Set<String> unmeasuredDevices = new HashSet<>();
        int measuredCandidates = 0;
        int unmeasuredCandidates = 0;
        for (CgroupIoSnapshot current : collection.snapshots()) {
            CgroupIoSnapshot previous =
                    collection.previousSnapshots().get(current.workload().cgroupPath());
            if (!comparable(current, previous)) {
                unmeasuredCandidates++;
                unmeasuredDevices.addAll(current.devices().keySet());
                continue;
            }

            boolean measured = current.devices().isEmpty() && previous.devices().isEmpty();
            boolean unmeasured = false;
            Set<String> deviceIds = new HashSet<>(current.devices().keySet());
            deviceIds.addAll(previous.devices().keySet());
            for (String device : deviceIds) {
                DeviceStats now = current.devices().get(device);
                DeviceStats before = previous.devices().get(device);
                if (!comparable(now, before)) {
                    unmeasuredDevices.add(device);
                    unmeasured = true;
                    continue;
                }
                devices.computeIfAbsent(device, ignored -> new ArrayList<>())
                        .add(new DeviceMeasurement(current, previous, now, before));
                measured = true;
            }
            // A group can contain both usable devices and a new or reset device.
            measuredCandidates += measured ? 1 : 0;
            unmeasuredCandidates += unmeasured ? 1 : 0;
        }

        Map<String, Candidate> winners = new TreeMap<>();
        CgroupIoSnapshot commonWinner = null;
        double score = 100;
        for (Map.Entry<String, List<DeviceMeasurement>> device : devices.entrySet()) {
            List<Candidate> candidates = candidates(device.getValue());
            if (candidates.isEmpty()) {
                continue;
            }
            Candidate winner = candidates.getFirst();
            double runnerScore = candidates.size() > 1 ? candidates.get(1).score() : 0;
            if (winner.score() < minimumScore
                    || (runnerScore > 0 && winner.score() / runnerScore < dominanceRatio)) {
                return unavailable(WorkloadAttribution.Status.NO_CANDIDATE, collection);
            }
            CgroupIoSnapshot snapshot = winner.measurement().current();
            if (commonWinner != null && !commonWinner.workload().equals(snapshot.workload())) {
                // Host PSI does not say which device is stalled; shares across devices cannot
                // establish that one device's leading workload caused the host pressure.
                return unavailable(WorkloadAttribution.Status.NO_CANDIDATE, collection);
            }
            commonWinner = snapshot;
            winners.put(device.getKey(), winner);
            score = Math.min(score, winner.score());
        }

        if (commonWinner == null) {
            WorkloadAttribution.Status status = unmeasuredCandidates > 0
                    ? WorkloadAttribution.Status.INSUFFICIENT_DATA
                    : WorkloadAttribution.Status.NO_CANDIDATE;
            return unavailable(status, collection);
        }

        List<DiagnosticEvidence> evidence = new ArrayList<>();
        for (Map.Entry<String, Candidate> winner : winners.entrySet()) {
            addDeviceEvidence(winner.getKey(), winner.getValue(), evidence);
        }
        DeviceMeasurement observation = winners.values().iterator().next().measurement();
        evidence.add(metric("observation.duration", observation.elapsedMillis(), "ms"));
        evidence.add(metric("devices.active", winners.size(), "count"));
        evidence.add(metric("devices.measured", devices.size(), "count"));
        evidence.add(metric("devices.unmeasured", unmeasuredDevices.size(), "count"));
        evidence.add(metric("candidates.measured", measuredCandidates, "count"));
        evidence.add(metric("candidates.unmeasured", unmeasuredCandidates, "count"));
        evidence.add(metric("limits.available", commonWinner.limitsAvailable() ? 1 : 0, "flag"));
        if (Double.isFinite(commonWinner.psiSome())) {
            evidence.add(metric("psi.some.avg10", commonWinner.psiSome(), "%"));
        }
        if (Double.isFinite(commonWinner.psiFull())) {
            evidence.add(metric("psi.full.avg10", commonWinner.psiFull(), "%"));
        }
        evidence.add(metric("score.activity", score, "points"));

        // These are shares among measured cgroups, not device capacity or proof of causality.
        return WorkloadAttribution.identified(
                commonWinner.workload(),
                score,
                WorkloadAttribution.Confidence.LOW,
                evidence,
                collection.coverage(),
                collection.collectedAt());
    }

    public void reset() {
        historyReset.run();
    }

    private boolean comparable(CgroupIoSnapshot current, CgroupIoSnapshot previous) {
        return previous != null
                && current.workload().equals(previous.workload())
                && current.collectedAt() > previous.collectedAt()
                && current.collectedAt() - previous.collectedAt() <= maximumObservationGapMillis;
    }

    private static boolean comparable(DeviceStats current, DeviceStats previous) {
        return current != null
                && previous != null
                && current.readBytes() >= previous.readBytes()
                && current.writeBytes() >= previous.writeBytes()
                && current.readOperations() >= previous.readOperations()
                && current.writeOperations() >= previous.writeOperations();
    }

    private static List<Candidate> candidates(List<DeviceMeasurement> measurements) {
        double totalBytes = measurements.stream()
                .mapToDouble(DeviceMeasurement::bytesPerSecond).sum();
        double totalOperations = measurements.stream()
                .mapToDouble(DeviceMeasurement::operationsPerSecond).sum();
        int activeAxes = (totalBytes > 0 ? 1 : 0) + (totalOperations > 0 ? 1 : 0);
        if (activeAxes == 0) {
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>();
        for (DeviceMeasurement measurement : measurements) {
            double byteShare = totalBytes > 0
                    ? measurement.bytesPerSecond() / totalBytes * 100 : 0;
            double operationShare = totalOperations > 0
                    ? measurement.operationsPerSecond() / totalOperations * 100 : 0;
            double score = Math.min(100, (byteShare + operationShare) / activeAxes);
            candidates.add(new Candidate(measurement, byteShare, operationShare, score));
        }
        candidates.sort(
                Comparator.comparingDouble(Candidate::score)
                        .reversed()
                        .thenComparing(candidate ->
                                candidate.measurement().current().workload().cgroupPath()));
        return candidates;
    }

    private static void addDeviceEvidence(
            String device, Candidate candidate, List<DiagnosticEvidence> evidence) {
        String prefix = "device." + device + ".";
        DeviceMeasurement measured = candidate.measurement();
        evidence.add(metric(prefix + "read.bytes.delta", measured.readBytesDelta(), "bytes"));
        evidence.add(metric(prefix + "write.bytes.delta", measured.writeBytesDelta(), "bytes"));
        evidence.add(metric(prefix + "read.operations.delta", measured.readOperationsDelta(), "count"));
        evidence.add(metric(prefix + "write.operations.delta", measured.writeOperationsDelta(), "count"));
        evidence.add(metric(prefix + "read.bytes.per-second", measured.readBytesPerSecond(), "bytes/s"));
        evidence.add(metric(prefix + "write.bytes.per-second", measured.writeBytesPerSecond(), "bytes/s"));
        evidence.add(metric(
                prefix + "read.operations.per-second", measured.readOperationsPerSecond(), "ops/s"));
        evidence.add(metric(
                prefix + "write.operations.per-second", measured.writeOperationsPerSecond(), "ops/s"));
        evidence.add(metric(prefix + "observed.byte.share", candidate.byteShare(), "%"));
        evidence.add(metric(prefix + "observed.operation.share", candidate.operationShare(), "%"));
        evidence.add(metric(prefix + "score.activity", candidate.score(), "points"));
        addLimitEvidence(prefix, device, measured, evidence);
    }

    private static void addLimitEvidence(
            String prefix,
            String device,
            DeviceMeasurement measured,
            List<DiagnosticEvidence> evidence) {
        CgroupIoSnapshot current = measured.current();
        CgroupIoSnapshot previous = measured.previous();
        DeviceLimit limit = current.limitsAvailable() ? current.limits().get(device) : null;
        if (limit == null) {
            return;
        }
        DeviceLimit before = previous.limitsAvailable() ? previous.limits().get(device) : null;
        addLimit(prefix + "limit.read.bytes", limit.readBytesPerSecond(),
                before == null ? null : before.readBytesPerSecond(),
                measured.readBytesPerSecond(), "bytes/s", evidence);
        addLimit(prefix + "limit.write.bytes", limit.writeBytesPerSecond(),
                before == null ? null : before.writeBytesPerSecond(),
                measured.writeBytesPerSecond(), "bytes/s", evidence);
        addLimit(prefix + "limit.read.operations", limit.readOperationsPerSecond(),
                before == null ? null : before.readOperationsPerSecond(),
                measured.readOperationsPerSecond(), "ops/s", evidence);
        addLimit(prefix + "limit.write.operations", limit.writeOperationsPerSecond(),
                before == null ? null : before.writeOperationsPerSecond(),
                measured.writeOperationsPerSecond(), "ops/s", evidence);
    }

    private static void addLimit(
            String prefix,
            Long current,
            Long previous,
            double rate,
            String unit,
            List<DiagnosticEvidence> evidence) {
        if (current == null) {
            return;
        }
        evidence.add(metric(prefix + ".per-second", current, unit));
        // This is only the local limit, and its current value cannot explain a changed interval.
        if (current.equals(previous)) {
            evidence.add(metric(prefix + ".usage", rate / current * 100, "%"));
        }
    }

    private static DiagnosticEvidence metric(String name, double value, String unit) {
        return new DiagnosticEvidence(name, value, unit);
    }

    private static WorkloadAttribution unavailable(
            WorkloadAttribution.Status status, CgroupMonitor.IoCollectionResult collection) {
        return WorkloadAttribution.unavailable(
                status, collection.coverage(), collection.collectedAt());
    }

    private record Candidate(
            DeviceMeasurement measurement, double byteShare, double operationShare, double score) {}

    private record DeviceMeasurement(
            CgroupIoSnapshot current,
            CgroupIoSnapshot previous,
            DeviceStats now,
            DeviceStats before) {

        long elapsedMillis() {
            return current.collectedAt() - previous.collectedAt();
        }

        long readBytesDelta() {
            return now.readBytes() - before.readBytes();
        }

        long writeBytesDelta() {
            return now.writeBytes() - before.writeBytes();
        }

        long readOperationsDelta() {
            return now.readOperations() - before.readOperations();
        }

        long writeOperationsDelta() {
            return now.writeOperations() - before.writeOperations();
        }

        double readBytesPerSecond() {
            return readBytesDelta() * 1_000.0 / elapsedMillis();
        }

        double writeBytesPerSecond() {
            return writeBytesDelta() * 1_000.0 / elapsedMillis();
        }

        double readOperationsPerSecond() {
            return readOperationsDelta() * 1_000.0 / elapsedMillis();
        }

        double writeOperationsPerSecond() {
            return writeOperationsDelta() * 1_000.0 / elapsedMillis();
        }

        double bytesPerSecond() {
            return readBytesPerSecond() + writeBytesPerSecond();
        }

        double operationsPerSecond() {
            return readOperationsPerSecond() + writeOperationsPerSecond();
        }
    }
}
