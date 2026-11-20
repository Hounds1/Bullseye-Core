package io.bullseye.core;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.config.PropertiesConfigurationLoader;
import io.bullseye.core.diagnostic.CpuPressureAttributor;
import io.bullseye.core.diagnostic.DiagnosticCoordinator;
import io.bullseye.core.diagnostic.DiagnosticEngine;
import io.bullseye.core.diagnostic.IoPressureAttributor;
import io.bullseye.core.diagnostic.MemoryPressureAttributor;
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
import io.bullseye.core.runtime.MonotonicClock;
import io.bullseye.core.state.InMemoryDiagnosticStateRepository;
import io.bullseye.core.state.publish.AsyncStatePublisher;
import io.bullseye.core.state.publish.AtomicFileStatePublisher;
import io.bullseye.core.state.publish.DiagnosticSnapshotJsonEncoder;
import io.bullseye.core.uog.ccc.CccClient;

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
        Clock wallClock = Clock.systemUTC();
        Clock timelineClock = new MonotonicClock(wallClock);
        BullseyeConfiguration configuration =
                new PropertiesConfigurationLoader().load(configurationPath);
        BullseyeConfiguration.Ccc ccc = configuration.ccc();
        CccClient cccClient = ccc.enabled()
                ? new CccClient(ccc.host(), ccc.port(), ccc.nodeId(), logger)
                : null;
        InMemoryRollingMetricWindow window =
                new InMemoryRollingMetricWindow(
                        configuration.windowDuration(), configuration.samplingInterval());
        DiagnosticSnapshot initialState =
                DiagnosticSnapshot.initial(
                        configuration.host(),
                        configuration.application(),
                        timelineClock.millis(),
                        wallClock.millis());
        InMemoryDiagnosticStateRepository stateRepository =
                new InMemoryDiagnosticStateRepository(initialState);
        Duration sampleFreshness = configuration.samplingInterval().multipliedBy(2);
        Duration maximumObservationGap =
                configuration.cgroup().attributionRefreshInterval().plus(sampleFreshness);
        CgroupMonitor cgroupMonitor =
                new CgroupMonitor(
                        configuration.cgroup(), CgroupMonitor.nioFileAccess(), timelineClock, logger);
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
                        new MemoryPressureAttributor(
                                cgroupMonitor,
                                configuration.cgroup().attributionMinimumScore(),
                                configuration.cgroup().attributionDominanceRatio(),
                                maximumObservationGap,
                                configuration.cgroup().attributionConfidence()),
                        new CpuPressureAttributor(
                                cgroupMonitor,
                                configuration.cgroup().attributionMinimumScore(),
                                configuration.cgroup().attributionDominanceRatio(),
                                maximumObservationGap,
                                configuration.cgroup().attributionConfidence()),
                        new IoPressureAttributor(
                                cgroupMonitor,
                                configuration.cgroup().attributionMinimumScore(),
                                configuration.cgroup().attributionDominanceRatio(),
                                maximumObservationGap),
                        sampleFreshness,
                        configuration.cgroup().attributionRefreshInterval());
        AsyncStatePublisher publisher =
                new AsyncStatePublisher(
                        new AtomicFileStatePublisher(
                                configuration.stateOutput(), new DiagnosticSnapshotJsonEncoder()),
                        logger);

        return new BullseyeRuntime(
                List.of(
                        new LinuxHostMetricCollector(new NioProcFileSource(), timelineClock, logger),
                        new LinuxPsiMetricCollector(new NioProcFileSource(), timelineClock, logger)),
                new HostMetricNormalizer(),
                window,
                coordinator,
                stateRepository,
                publisher,
                logger,
                timelineClock,
                wallClock,
                configuration.samplingInterval(),
                configuration.windowDuration(),
                cgroupMonitor,
                cccClient);
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
