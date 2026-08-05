package com.ngaxavilabs.simulator;

import com.ngaxavilabs.simulator.model.PumpSpec;
import com.ngaxavilabs.simulator.model.PumpType;
import com.ngaxavilabs.simulator.model.RunState;
import com.ngaxavilabs.simulator.model.Scenario;
import com.ngaxavilabs.simulator.model.TelemetryEvent;
import com.ngaxavilabs.simulator.scenario.BearingWearScenario;
import com.ngaxavilabs.simulator.scenario.CavitationScenario;
import com.ngaxavilabs.simulator.scenario.DryRunScenario;
import com.ngaxavilabs.simulator.scenario.FaultedScenario;
import com.ngaxavilabs.simulator.scenario.NormalScenario;
import com.ngaxavilabs.simulator.scenario.ScenarioModel;
import com.ngaxavilabs.simulator.scenario.ScenarioRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** State machine, scenario progression and seeded reproducibility. */
class PumpSimulatorTest {

    private static PumpSpec spec() {
        return new PumpSpec("tenant-a", "plant-01", "pump-001", PumpType.CENTRIFUGAL,
                50, 45, 40, 1450, 7.5,
                1.3, 1.8, 58, 0.30, 1000, "fw-2.4.1");
    }

    /** Registry backed by a plain list — no CDI container needed for unit tests. */
    @SuppressWarnings("unchecked")
    private static ScenarioRegistry registry() {
        List<ScenarioModel> models = List.of(
                new NormalScenario(), new BearingWearScenario(), new CavitationScenario(),
                new DryRunScenario(), new FaultedScenario());
        InvocationHandler handler = (proxy, method, args) -> {
            if ("iterator".equals(method.getName())) {
                return models.iterator();
            }
            throw new UnsupportedOperationException(method.getName());
        };
        Instance<ScenarioModel> instance = (Instance<ScenarioModel>) Proxy.newProxyInstance(
                PumpSimulatorTest.class.getClassLoader(), new Class<?>[]{Instance.class}, handler);
        return new ScenarioRegistry(instance);
    }

    private static com.ngaxavilabs.simulator.sim.PumpSimulator sim(Scenario scenario, double ramp) {
        return new com.ngaxavilabs.simulator.sim.PumpSimulator(
                spec(), registry(), 25.0, "1.0.0", 42L, scenario, ramp, 1.0);
    }

    private static List<TelemetryEvent> run(com.ngaxavilabs.simulator.sim.PumpSimulator s, int ticks) {
        List<TelemetryEvent> events = new ArrayList<>(ticks);
        Instant t0 = Instant.parse("2026-08-02T10:00:00Z");
        for (int i = 0; i < ticks; i++) {
            events.add(s.tick(t0.plusSeconds(i), 1.0));
        }
        return events;
    }

    @Test
    void followsStartupSequence() {
        var s = sim(Scenario.NORMAL, 1800);
        assertThat(s.runState()).isEqualTo(RunState.OFF);

        // Stopped: no flow.
        assertThat(run(s, 2).getLast().measurements().flowRateM3h()).isZero();

        s.start();
        assertThat(s.runState()).isEqualTo(RunState.STARTING);

        List<TelemetryEvent> events = run(s, 10);
        assertThat(events.getFirst().runState()).isEqualTo(RunState.STARTING);
        assertThat(events.getLast().runState()).isEqualTo(RunState.NORMAL);
        // Speed ramps rather than stepping.
        assertThat(events.getFirst().measurements().motorSpeedRpm())
                .isLessThan(events.getLast().measurements().motorSpeedRpm());
        assertThat(events.getLast().measurements().flowRateM3h()).isGreaterThan(30);
    }

    @Test
    void bearingWearProgressesThroughHealthStates() {
        var s = sim(Scenario.BEARING_WEAR, 300);
        s.start();
        List<TelemetryEvent> events = run(s, 400);

        List<RunState> seen = events.stream().map(TelemetryEvent::runState).distinct().toList();
        assertThat(seen).contains(RunState.STARTING, RunState.NORMAL, RunState.DEGRADING,
                RunState.CRITICAL, RunState.FAULTED);

        // Vibration must climb monotonically in the trend, not jump around.
        double earlyVibration = events.get(20).measurements().vibrationRmsMmS();
        double lateVibration = events.get(250).measurements().vibrationRmsMmS();
        assertThat(lateVibration).isGreaterThan(earlyVibration + 2.0);

        // Bearing temperature follows the vibration.
        assertThat(events.get(250).measurements().bearingTemperatureDeC())
                .isGreaterThan(events.get(20).measurements().bearingTemperatureDeC());
    }

    @Test
    void cavitationStarvesSuctionAndDestabilisesFlow() {
        var s = sim(Scenario.CAVITATION, 200);
        s.start();
        List<TelemetryEvent> events = run(s, 220);

        double earlySuction = events.get(15).measurements().suctionPressureBar();
        double lateSuction = events.get(190).measurements().suctionPressureBar();
        assertThat(lateSuction).isLessThan(earlySuction);

        // Flow becomes unstable: later variance well above the healthy noise band.
        assertThat(variance(events.subList(150, 200))).isGreaterThan(variance(events.subList(10, 60)));
    }

    @Test
    void dryRunCollapsesFlowAndHeatsCasing() {
        var s = sim(Scenario.DRY_RUN, 120);
        s.start();
        List<TelemetryEvent> events = run(s, 300);

        TelemetryEvent early = events.get(15);
        TelemetryEvent late = events.get(150);
        double peakCasing = events.stream()
                .mapToDouble(e -> e.measurements().casingTemperatureC())
                .max().orElseThrow();

        assertThat(late.measurements().flowRateM3h()).isLessThan(1.0);
        assertThat(peakCasing).isGreaterThan(early.measurements().casingTemperatureC() + 15);
        assertThat(events.getLast().runState()).isEqualTo(RunState.FAULTED);
    }

    @Test
    void faultedScenarioTripsImmediatelyAndLatches() {
        var s = sim(Scenario.NORMAL, 1800);
        s.start();
        run(s, 10);
        assertThat(s.runState()).isEqualTo(RunState.NORMAL);

        s.setScenario(Scenario.FAULTED, null);
        List<TelemetryEvent> events = run(s, 15);

        assertThat(events.getFirst().runState()).isEqualTo(RunState.FAULTED);
        assertThat(events.getLast().runState()).isEqualTo(RunState.FAULTED);
        // Coasts down to standstill and stays there.
        assertThat(events.getLast().measurements().motorSpeedRpm()).isZero();
        assertThat(events.getLast().measurements().flowRateM3h()).isZero();
    }

    @Test
    void sequenceNumbersAreMonotonicAndMessageIdsUnique() {
        var s = sim(Scenario.NORMAL, 1800);
        s.start();
        List<TelemetryEvent> events = run(s, 50);

        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).sequenceNumber()).isEqualTo(i + 1);
        }
        assertThat(events.stream().map(TelemetryEvent::messageId).distinct()).hasSize(50);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.eventTime()).isNotNull();
            assertThat(e.generatedAt()).isNotNull();
            assertThat(e.schemaVersion()).isEqualTo("1.0.0");
        });
    }

    @Test
    void sameSeedReplaysIdenticalMeasurements() {
        var a = sim(Scenario.BEARING_WEAR, 300);
        var b = sim(Scenario.BEARING_WEAR, 300);
        a.start();
        b.start();

        List<TelemetryEvent> runA = run(a, 120);
        List<TelemetryEvent> runB = run(b, 120);

        for (int i = 0; i < runA.size(); i++) {
            assertThat(runA.get(i).measurements()).isEqualTo(runB.get(i).measurements());
            assertThat(runA.get(i).runState()).isEqualTo(runB.get(i).runState());
        }
        // messageId is deliberately not reproducible — it is a delivery identity.
        assertThat(runA.getFirst().messageId()).isNotEqualTo(runB.getFirst().messageId());
    }

    @Test
    void stoppedPumpDoesNotDegrade() {
        var s = sim(Scenario.BEARING_WEAR, 100);
        run(s, 300); // never started
        assertThat(s.scenarioElapsedSeconds()).isZero();
        assertThat(s.runState()).isEqualTo(RunState.OFF);
    }

    @Test
    void topicIsPerAsset() {
        assertThat(spec().telemetryTopic())
                .isEqualTo("tenant/tenant-a/site/plant-01/equipment/pump-001/telemetry");
    }

    private static double variance(List<TelemetryEvent> events) {
        double mean = events.stream().mapToDouble(e -> e.measurements().flowRateM3h()).average().orElse(0);
        return events.stream()
                .mapToDouble(e -> Math.pow(e.measurements().flowRateM3h() - mean, 2))
                .average().orElse(0);
    }
}
