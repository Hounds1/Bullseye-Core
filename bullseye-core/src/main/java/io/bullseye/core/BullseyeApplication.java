package io.bullseye.core;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.config.PropertiesConfigurationLoader;
import io.bullseye.core.diagnostic.DiagnosticCoordinator;
import io.bullseye.core.diagnostic.DiagnosticEngine;
import io.bullseye.core.diagnostic.PressureAttributor;
import io.bullseye.core.diagnostic.rule.CpuPressureRule;
import io.bullseye.core.diagnostic.rule.IoPressureRule;
import io.bullseye.core.diagnostic.rule.MemoryPressureRule;
import io.bullseye.core.linux.cgroup.CgroupMonitor;
import io.bullseye.core.linux.proc.LinuxHostMetricCollector;
import io.bullseye.core.linux.proc.NioProcFileSource;
import io.bullseye.core.linux.psi.LinuxPsiMetricCollector;
import io.bullseye.core.logging.BullseyeLogger;
import io.bullseye.core.metric.HostMetricNormalizer;
import io.bullseye.core.metric.InMemoryRollingMetricWindow;
import io.bullseye.core.runtime.BullseyeRuntime;
import io.bullseye.core.state.InMemoryDiagnosticStateRepository;
import io.bullseye.core.state.publish.AsyncStatePublisher;
import io.bullseye.core.state.publish.AtomicFileStatePublisher;
import io.bullseye.core.state.publish.DiagnosticSnapshotJsonEncoder;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class BullseyeApplication {

    private BullseyeApplication() {}

    public static void main(String[] args) {
        BullseyeLogger logger = BullseyeLogger.system();
        try {
            if (!isLinux()) {
                throw new IllegalStateException("Linux host required");
            }
            Path configurationPath =
                    args.length == 0 ? Path.of("config", "bullseye.properties") : Path.of(args[0]);
            BullseyeRuntime runtime = createRuntime(configurationPath, logger);
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "bullseye-shutdown"));
            runtime.start();
        } catch (Exception failure) {
            logger.interrupted("System activation failed.", failure);
            System.exit(1);
        }
    }

    static BullseyeRuntime createRuntime(Path configurationPath, BullseyeLogger logger)
            throws Exception {
        Clock clock = Clock.systemUTC();
        BullseyeConfiguration configuration =
                new PropertiesConfigurationLoader().load(configurationPath);
        InMemoryRollingMetricWindow window =
                new InMemoryRollingMetricWindow(
                        configuration.windowDuration(), configuration.samplingInterval());
        DiagnosticSnapshot initialState =
                DiagnosticSnapshot.initial(
                        configuration.host(), configuration.application(), clock.millis());
        InMemoryDiagnosticStateRepository stateRepository =
                new InMemoryDiagnosticStateRepository(initialState);
        Duration sampleFreshness = configuration.samplingInterval().multipliedBy(2);
        CgroupMonitor cgroupMonitor =
                new CgroupMonitor(
                        configuration.cgroup(), CgroupMonitor.nioFileAccess(), clock, logger);
        DiagnosticEngine engine =
                new DiagnosticEngine(
                        List.of(
                                new CpuPressureRule(
                                        configuration.cpuDiagnostics(), sampleFreshness),
                                new MemoryPressureRule(
                                        configuration.memoryDiagnostics(), sampleFreshness),
                                new IoPressureRule(
                                        configuration.ioDiagnostics(), sampleFreshness)));
        DiagnosticCoordinator coordinator =
                new DiagnosticCoordinator(
                        window,
                        engine,
                        stateRepository,
                        () -> UUID.randomUUID().toString(),
                        cgroupMonitor::snapshots,
                        new PressureAttributor(
                                configuration.cgroup().attributionMinimumScore(),
                                configuration.cgroup().attributionDominanceRatio(),
                                configuration.cgroup().samplingInterval().multipliedBy(3)));
        AsyncStatePublisher publisher =
                new AsyncStatePublisher(
                        new AtomicFileStatePublisher(
                                configuration.stateOutput(), new DiagnosticSnapshotJsonEncoder()),
                        logger);

        return new BullseyeRuntime(
                List.of(
                        new LinuxHostMetricCollector(new NioProcFileSource(), clock),
                        new LinuxPsiMetricCollector(new NioProcFileSource(), clock, logger)),
                new HostMetricNormalizer(),
                window,
                coordinator,
                stateRepository,
                publisher,
                logger,
                clock,
                configuration.samplingInterval(),
                configuration.windowDuration(),
                cgroupMonitor,
                configuration.cgroup().samplingInterval());
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
