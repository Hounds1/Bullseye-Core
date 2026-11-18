package io.bullseye.core.linux.cgroup;

import io.bullseye.common.workload.WorkloadIdentity;

import java.util.Map;
import java.util.Objects;

public record CgroupIoSnapshot(
        WorkloadIdentity workload,
        Map<String, DeviceStats> devices,
        Map<String, DeviceLimit> limits,
        boolean limitsAvailable,
        double psiSome,
        double psiFull,
        long collectedAt) {

    public CgroupIoSnapshot {
        Objects.requireNonNull(workload, "workload");
        devices = Map.copyOf(Objects.requireNonNull(devices, "devices"));
        limits = Map.copyOf(Objects.requireNonNull(limits, "limits"));
        devices.keySet().forEach(CgroupIoSnapshot::requireDeviceId);
        limits.keySet().forEach(CgroupIoSnapshot::requireDeviceId);
        if (!limitsAvailable && !limits.isEmpty()) {
            throw new IllegalArgumentException("Unavailable io.max cannot contain device limits");
        }
        if (collectedAt < 0) {
            throw new IllegalArgumentException("IO timestamp must not be negative");
        }
        requirePressure(psiSome);
        requirePressure(psiFull);
    }

    public record DeviceStats(
            long readBytes,
            long writeBytes,
            long readOperations,
            long writeOperations) {

        public DeviceStats {
            if (readBytes < 0 || writeBytes < 0 || readOperations < 0 || writeOperations < 0) {
                throw new IllegalArgumentException("IO counters must not be negative");
            }
        }
    }

    // Null values are unlimited. Missing device entries are unlimited when io.max is available.
    public record DeviceLimit(
            Long readBytesPerSecond,
            Long writeBytesPerSecond,
            Long readOperationsPerSecond,
            Long writeOperationsPerSecond) {

        public DeviceLimit {
            requireLimit(readBytesPerSecond);
            requireLimit(writeBytesPerSecond);
            requireLimit(readOperationsPerSecond);
            requireLimit(writeOperationsPerSecond);
        }

        private static void requireLimit(Long value) {
            if (value != null && value <= 0) {
                throw new IllegalArgumentException("IO limits must be positive when present");
            }
        }
    }

    static void requireDeviceId(String device) {
        if (!device.matches("(0|[1-9][0-9]*):(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("Invalid IO device: " + device);
        }
        String[] parts = device.split(":");
        Long.parseLong(parts[0]);
        Long.parseLong(parts[1]);
    }

    private static void requirePressure(double value) {
        if (!Double.isNaN(value) && (!Double.isFinite(value) || value < 0)) {
            throw new IllegalArgumentException("IO PSI must be non-negative or unavailable");
        }
    }
}
