package io.bullseye.core.logging;

import io.bullseye.core.diagnostic.DiagnosticTransition;

import java.io.PrintStream;
import java.util.Objects;

public final class BullseyeLogger {

    private final PrintStream output;
    private final PrintStream error;

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
        status(transition.event().reason()
                + " previous=" + transition.previous().severity()
                + " current=" + transition.current().severity()
                + " resource=" + transition.event().resource());
    }

    public void interrupted(String message, Throwable failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = failure.getClass().getSimpleName();
        }
        error.println("[BULLSEYE] " + message + " cause=" + sanitize(detail));
    }

    private static String sanitize(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
