package io.bullseye.core.publish;

import io.bullseye.common.DiagnosticSnapshot;

public interface StatePublisher {

    void publish(DiagnosticSnapshot state) throws Exception;
}
