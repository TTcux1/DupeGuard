package dev.dupeguard.core;

/** Насколько срочно стаффу стоит посмотреть на находку. */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public boolean isAtLeast(Severity other) {
        return compareTo(other) >= 0;
    }
}
