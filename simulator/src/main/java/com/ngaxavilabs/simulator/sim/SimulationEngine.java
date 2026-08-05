package com.ngaxavilabs.simulator.sim;

import com.ngaxavilabs.simulator.config.SimulatorConfig;
import com.ngaxavilabs.simulator.model.PumpSpec;
import com.ngaxavilabs.simulator.model.TelemetryEvent;
import com.ngaxavilabs.simulator.mqtt.DeliveryFaults;
import com.ngaxavilabs.simulator.mqtt.TelemetryPublisher;
import com.ngaxavilabs.simulator.scenario.ScenarioRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the fleet and its clock. Each pump is scheduled independently at its own
 * sample interval (1 Hz by default), so a fleet can mix fast and slow assets.
 *
 * <p>The tick measures real elapsed time rather than assuming the nominal
 * interval — thermal lag and scenario ramps stay accurate even if the scheduler
 * drifts under load.
 */
@ApplicationScoped
public class SimulationEngine {

    private static final Logger LOG = Logger.getLogger(SimulationEngine.class);

    @Inject
    SimulatorConfig config;

    @Inject
    ScenarioRegistry registry;

    @Inject
    TelemetryPublisher publisher;

    private final Map<String, PumpSimulator> pumps = new LinkedHashMap<>();
    private final Map<String, Long> lastTickNanos = new LinkedHashMap<>();
    private ScheduledExecutorService scheduler;

    void onStart(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LOG.warn("Pump simulator is disabled (simulator.enabled=false)");
            return;
        }
        if (config.pumps().isEmpty()) {
            LOG.warn("No pumps configured under simulator.pumps — nothing to simulate");
            return;
        }

        publisher.setFaults(new DeliveryFaults(
                config.delivery().duplicateProbability(),
                config.delivery().lateProbability(),
                config.delivery().lateDelayMs(),
                config.delivery().outOfOrderProbability(),
                config.delivery().dropProbability()));

        scheduler = Executors.newScheduledThreadPool(
                Math.min(4, Math.max(1, config.pumps().size())),
                r -> {
                    Thread t = new Thread(r, "pump-sim");
                    t.setDaemon(true);
                    return t;
                });

        config.pumps().forEach((equipmentId, def) -> {
            PumpSpec spec = new PumpSpec(
                    def.tenantId(), def.siteId(), equipmentId, def.type(),
                    def.ratedFlowM3h(), def.bepFlowM3h(), def.ratedHeadM(),
                    def.ratedSpeedRpm(), def.ratedPowerKw(),
                    def.suctionPressureBar(), def.baselineVibrationMmS(), def.baselineBearingTempC(),
                    def.staticHeadFraction(), def.sampleIntervalMs(), def.firmwareVersion());

            PumpSimulator sim = new PumpSimulator(
                    spec, registry, config.ambientTemperatureC(), config.schemaVersion(),
                    config.seed(), def.scenario(), def.scenarioRampSeconds(), def.speedRatio());

            if (def.autoStart()) {
                sim.start();
            }

            pumps.put(equipmentId, sim);
            lastTickNanos.put(equipmentId, System.nanoTime());

            scheduler.scheduleAtFixedRate(
                    () -> tick(equipmentId),
                    def.sampleIntervalMs(),
                    def.sampleIntervalMs(),
                    TimeUnit.MILLISECONDS);

            LOG.infof("Simulating %s -> %s (scenario=%s, interval=%dms)",
                    equipmentId, spec.telemetryTopic(), def.scenario(), def.sampleIntervalMs());
        });
    }

    void onShutdown(@Observes ShutdownEvent event) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void tick(String equipmentId) {
        PumpSimulator sim = pumps.get(equipmentId);
        if (sim == null) {
            return;
        }
        try {
            long now = System.nanoTime();
            long previous = lastTickNanos.getOrDefault(equipmentId, now);
            double dtSeconds = Math.max(1e-3, (now - previous) / 1_000_000_000.0);
            lastTickNanos.put(equipmentId, now);

            TelemetryEvent event = sim.tick(Instant.now(), dtSeconds);
            publisher.publish(sim.spec().telemetryTopic(), event);
        } catch (RuntimeException e) {
            // A failing tick must not kill the scheduled task for this pump.
            LOG.errorf(e, "Tick failed for %s", equipmentId);
        }
    }

    public Collection<PumpSimulator> pumps() {
        return pumps.values();
    }

    public Optional<PumpSimulator> pump(String equipmentId) {
        return Optional.ofNullable(pumps.get(equipmentId));
    }
}
