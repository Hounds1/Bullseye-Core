package io.bullseye.core.logging;

import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.diagnostic.DiagnosticTransition;

import java.io.PrintStream;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class BullseyeLogger {

    private static final int MAXIMUM_INTERRUPTED_COMPONENTS = 256;
    private final PrintStream output;
    private final PrintStream error;
    private final Set<String> interruptedComponents = new LinkedHashSet<>();

    public BullseyeLogger(PrintStream output, PrintStream error) {
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
    }

    public static BullseyeLogger system() {
        return new BullseyeLogger(System.out, System.err);
    }

    public void status(String message) {
        output.println("[BULLSEYE] " + message);
    }

    public void transition(DiagnosticTransition transition) {
        status(
                transition.event().reason()
                        + " previous="
                        + transition.previous().severity()
                        + " current="
                        + transition.current().severity()
                        + " resource="
                        + transition.event().resource());

        if (attributionChanged(transition)) {
            if (transition.current().attribution().resolved()) {
                String announcement =
                        transition.current().severity() == Severity.CRITICAL
                                        && transition.current().attribution().confidence()
                                                == WorkloadAttribution.Confidence.HIGH
                                ? "Critical workload identified."
                                : "Pressure source identified.";
                status(
                        announcement
                                + " resource="
                                + transition.current().resource()
                                + " workload="
                                + transition.current().attribution().workload().name()
                                + " confidence="
                                + transition.current().attribution().confidence()
                                + " score="
                                + String.format(
                                        Locale.ROOT,
                                        "%.1f",
                                        transition.current().attribution().score()));
            } else if (transition.current().attribution().status()
                    != WorkloadAttribution.Status.NOT_ATTEMPTED) {
                status(
                        "Pressure source attribution unavailable. resource="
                                + transition.current().resource()
                                + " reason="
                                + transition.current().attribution().status());
            }
        }
    }

    public synchronized void componentInterrupted(String component, Throwable failure) {
        Objects.requireNonNull(component, "component");
        if (!interruptedComponents.contains(component)
                && interruptedComponents.size() >= MAXIMUM_INTERRUPTED_COMPONENTS) {
            interruptedComponents.remove(interruptedComponents.iterator().next());
        }
        if (interruptedComponents.add(component)) {
            interrupted("Telemetry component unavailable. component=" + component, failure);
        }
    }

    public synchronized void componentRestored(String component) {
        Objects.requireNonNull(component, "component");
        if (interruptedComponents.remove(component)) {
            status("Telemetry component restored. component=" + component);
        }
    }

    public void interrupted(String message, Throwable failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = failure.getClass().getSimpleName();
        }
        error.println("[BULLSEYE] " + message + " cause=" + sanitize(detail));
    }

    private static boolean attributionChanged(DiagnosticTransition transition) {
        if (transition.current().severity().ordinal() < Severity.HIGH.ordinal()) {
            return false;
        }
        return !transition
                        .previous()
                        .attribution()
                        .workload()
                        .equals(transition.current().attribution().workload())
                || transition.previous().attribution().confidence()
                        != transition.current().attribution().confidence()
                || transition.previous().attribution().status()
                        != transition.current().attribution().status()
                || transition.previous().resource() != transition.current().resource()
                || transition.previous().severity().ordinal() < Severity.HIGH.ordinal();
    }

    private static String sanitize(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
