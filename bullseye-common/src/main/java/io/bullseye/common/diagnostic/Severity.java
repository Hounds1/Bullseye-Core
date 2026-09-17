package io.bullseye.common.diagnostic;

public enum Severity {
    NORMAL,
    ELEVATED,
    HIGH,
    CRITICAL;

    public boolean isHigherThan(Severity other) {
        return ordinal() > other.ordinal();
    }
}
