package io.bullseye.core.logging;

import io.bullseye.common.diagnostic.DiagnosticSnapshot.ResourceDiagnosis;
import io.bullseye.common.diagnostic.ResourceType;
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
        if (!transition.current().resources().isEmpty()) {
            resourceTransitions(transition);
            return;
        }

        announceState(
                transition.event().reason(),
                transition.previous().severity(),
                transition.current().severity(),
                transition.event().resource());
        if (attributionChanged(transition)) {
            announceAttribution(
                    transition.current().resource(),
                    transition.current().severity(),
                    transition.current().attribution());
        }
    }

    private void resourceTransitions(DiagnosticTransition transition) {
        boolean primaryStateAnnounced = false;
        for (ResourceType resource : ResourceType.values()) {
            ResourceDiagnosis current = transition.current().resources().get(resource);
            if (current == null) {
                continue;
            }
            ResourceDiagnosis previous = transition.previous().diagnosis(resource);
            boolean stateChanged = previous.severity() != current.severity()
                    || previous.state() != current.state();
            if (stateChanged) {
                announceState(current.reason(), previous.severity(), current.severity(), resource);
                primaryStateAnnounced |= resource == transition.current().resource();
            }
            if (current.severity().ordinal() >= Severity.HIGH.ordinal()
                    && (previous.severity().ordinal() < Severity.HIGH.ordinal()
                            || !previous.attribution().sameSourceAs(current.attribution()))) {
                announceAttribution(resource, current.severity(), current.attribution());
            }
        }

        if (transition.previous().resource() != transition.current().resource()
                && transition.current().resource() != ResourceType.UNKNOWN
                && !primaryStateAnnounced) {
            status("Primary resource reassigned. previous=" + transition.previous().resource()
                    + " current=" + transition.current().resource()
                    + " severity=" + transition.current().severity());
        }
    }

    private void announceState(
            String reason, Severity previous, Severity current, ResourceType resource) {
        status(reason + " previous=" + previous + " current=" + current + " resource=" + resource);
    }

    private void announceAttribution(
            ResourceType resource, Severity severity, WorkloadAttribution attribution) {
        if (attribution.resolved()) {
            String announcement = severity == Severity.CRITICAL
                            && attribution.confidence() == WorkloadAttribution.Confidence.HIGH
                    ? "Critical workload identified."
                    : "Pressure source identified.";
            status(announcement
                    + " resource=" + resource
                    + " workload=" + attribution.workload().name()
                    + " confidence=" + attribution.confidence()
                    + " score=" + String.format(Locale.ROOT, "%.1f", attribution.score())
                    + coverageSuffix(attribution.coverage()));
        } else if (attribution.status() != WorkloadAttribution.Status.NOT_ATTEMPTED) {
            status("Pressure source attribution unavailable. resource=" + resource
                    + " reason=" + attribution.status()
                    + coverageSuffix(attribution.coverage()));
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
        return !transition.previous().attribution().sameSourceAs(transition.current().attribution())
                || transition.previous().resource() != transition.current().resource()
                || transition.previous().severity().ordinal() < Severity.HIGH.ordinal();
    }

    private static String sanitize(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static String coverageSuffix(WorkloadAttribution.Coverage coverage) {
        if (coverage == null || coverage.complete()) {
            return "";
        }
        return " coverage=PARTIAL collected="
                + coverage.collectedGroups()
                + " failed="
                + coverage.failedGroups()
                + " discoveryFailed="
                + coverage.discoveryFailed()
                + " discoveryLimited="
                + coverage.discoveryLimited();
    }
}
