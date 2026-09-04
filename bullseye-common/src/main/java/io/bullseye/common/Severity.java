package io.bullseye.common;

public enum Severity {
    NORMAL,
    ELEVATED,
    HIGH,
    CRITICAL;

    public boolean isHigherThan(Severity other) {
        return ordinal() > other.ordinal();
    }
}
