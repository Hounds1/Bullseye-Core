package io.bullseye.core.state.publish;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;

public interface StatePublisher {

    void publish(DiagnosticSnapshot state) throws Exception;
}
