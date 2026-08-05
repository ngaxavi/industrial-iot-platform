package com.ngaxavilabs.simulator.sim;

import com.ngaxavilabs.simulator.model.Measurements;
import com.ngaxavilabs.simulator.model.PumpSpec;
import com.ngaxavilabs.simulator.model.Quality;
import com.ngaxavilabs.simulator.model.RunState;
import com.ngaxavilabs.simulator.model.Scenario;
import com.ngaxavilabs.simulator.model.TelemetryEvent;
import com.ngaxavilabs.simulator.physics.Modifiers;
import com.ngaxavilabs.simulator.physics.PumpPhysics;
import com.ngaxavilabs.simulator.physics.ThermalState;
import com.ngaxavilabs.simulator.scenario.ScenarioModel;
import com.ngaxavilabs.simulator.scenario.ScenarioRegistry;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;

/**
 * One simulated pump: the section 4 state machine wrapped around the physics.
 *
 * <pre>OFF -> STARTING -> NORMAL -> DEGRADING -> CRITICAL -> FAULTED</pre>
 *
 * <p>Health transitions are driven by the active scenario's severity signal, so
 * the state a pump reports is always consistent with the numbers it is sending.
 *
 * <p>Randomness is seeded per pump ({@code masterSeed} + equipmentId hash), so a
 * given fleet configuration replays identically — which is what makes ingestion
 * and alerting assertions reproducible.
 *
 * <p>All mutating methods are synchronised: the scheduler ticks while the
 * control API may be switching scenarios.
 */
public class PumpSimulator {

    /** Seconds to ramp from standstill to setpoint. */
    private static final double STARTUP_SECONDS = 6.0;
    /** Seconds to coast down after a trip. */
    private static final double COASTDOWN_SECONDS = 4.0;

    private final PumpSpec spec;
    private final ScenarioRegistry registry;
    private final PumpPhysics physics;
    private final Random rng;
    private final ThermalState thermal;
    private final String schemaVersion;

    private RunState runState = RunState.OFF;
    private Scenario scenario;
    private double scenarioRampSeconds;
    private double scenarioElapsedSeconds;

    private double speedRatio;
    private double actualSpeedRpm;
    private double startupElapsedSeconds;

    private long sequenceNumber;
    private double lastSeverity;

    public PumpSimulator(PumpSpec spec, ScenarioRegistry registry, double ambientC, String schemaVersion,
                         long masterSeed, Scenario scenario, double scenarioRampSeconds, double speedRatio) {
        this.spec = spec;
        this.registry = registry;
        this.physics = new PumpPhysics(ambientC);
        this.schemaVersion = schemaVersion;
        this.rng = new Random(masterSeed * 31L + spec.equipmentId().hashCode());
        this.thermal = new ThermalState(ambientC);
        this.scenario = scenario;
        this.scenarioRampSeconds = scenarioRampSeconds;
        this.speedRatio = speedRatio;
    }

    /** Advances the simulation by {@code dtSeconds} and returns the snapshot. */
    public synchronized TelemetryEvent tick(Instant eventTime, double dtSeconds) {
        double commandedSpeed = spec.ratedSpeedRpm() * speedRatio;

        // Scenarios only progress while the shaft is turning: a pump sitting in
        // OFF does not wear its bearings out.
        if (runState.isRunning()) {
            scenarioElapsedSeconds += dtSeconds;
        }

        Modifiers mods = new Modifiers(spec.baselineSuctionPressureBar());
        ScenarioModel model = registry.get(scenario);
        model.apply(mods, spec, scenarioElapsedSeconds, scenarioRampSeconds, rng);
        lastSeverity = mods.severity;

        advanceState(mods, commandedSpeed, dtSeconds);

        // "Running" is about the shaft, not the health state — a tripped pump
        // still coasts down, and that deceleration must be visible in telemetry.
        boolean running = actualSpeedRpm > 1.0;
        Measurements measurements =
                physics.step(spec, actualSpeedRpm, running, mods, thermal, dtSeconds, rng);

        sequenceNumber++;
        return new TelemetryEvent(
                schemaVersion,
                UUID.randomUUID().toString(),
                spec.tenantId(),
                spec.siteId(),
                spec.equipmentId(),
                eventTime,
                Instant.now(),
                sequenceNumber,
                scenario,
                runState,
                Quality.GOOD,
                measurements);
    }

    private void advanceState(Modifiers mods, double commandedSpeed, double dtSeconds) {
        boolean tripped = mods.trip || mods.severity >= 0.90;

        switch (runState) {
            case OFF -> actualSpeedRpm = 0.0;

            case STARTING -> {
                startupElapsedSeconds += dtSeconds;
                double ratio = Math.min(1.0, startupElapsedSeconds / STARTUP_SECONDS);
                // S-curve ramp: soft-start rather than a step change.
                actualSpeedRpm = commandedSpeed * (ratio * ratio * (3.0 - 2.0 * ratio));
                if (ratio >= 1.0) {
                    runState = tripped ? RunState.FAULTED : RunState.fromSeverity(mods.severity);
                }
            }

            case NORMAL, DEGRADING, CRITICAL -> {
                actualSpeedRpm = commandedSpeed;
                runState = tripped ? RunState.FAULTED : RunState.fromSeverity(mods.severity);
            }

            case FAULTED -> {
                // Latched: the pump stays faulted until explicitly reset.
                // Coasts to standstill over COASTDOWN_SECONDS.
                double step = Math.max(commandedSpeed, 1.0) * dtSeconds / COASTDOWN_SECONDS;
                actualSpeedRpm = Math.max(0.0, actualSpeedRpm - step);
                if (actualSpeedRpm < 1.0) {
                    actualSpeedRpm = 0.0;
                }
            }
        }
    }

    // --- control surface ----------------------------------------------------

    public synchronized void start() {
        if (runState == RunState.OFF) {
            runState = RunState.STARTING;
            startupElapsedSeconds = 0.0;
        }
    }

    public synchronized void stop() {
        runState = RunState.OFF;
        actualSpeedRpm = 0.0;
        startupElapsedSeconds = 0.0;
    }

    /** Clears a latched fault and returns the pump to OFF, ready to restart. */
    public synchronized void reset() {
        runState = RunState.OFF;
        actualSpeedRpm = 0.0;
        startupElapsedSeconds = 0.0;
        scenarioElapsedSeconds = 0.0;
        scenario = Scenario.NORMAL;
    }

    /** Switches the active scenario and restarts its ramp from zero. */
    public synchronized void setScenario(Scenario next, Double rampSeconds) {
        this.scenario = next;
        this.scenarioElapsedSeconds = 0.0;
        if (rampSeconds != null && rampSeconds > 0) {
            this.scenarioRampSeconds = rampSeconds;
        }
        // A scenario change on a faulted pump implies an intent to recover.
        if (runState == RunState.FAULTED && next != Scenario.FAULTED) {
            runState = RunState.STARTING;
            startupElapsedSeconds = 0.0;
        }
    }

    public synchronized void setSpeedRatio(double ratio) {
        this.speedRatio = PumpPhysics.clamp(ratio, 0.0, 1.2);
    }

    // --- inspection ---------------------------------------------------------

    public PumpSpec spec() {
        return spec;
    }

    public String equipmentId() {
        return spec.equipmentId();
    }

    public synchronized RunState runState() {
        return runState;
    }

    public synchronized Scenario scenario() {
        return scenario;
    }

    public synchronized double scenarioElapsedSeconds() {
        return scenarioElapsedSeconds;
    }

    public synchronized double scenarioRampSeconds() {
        return scenarioRampSeconds;
    }

    public synchronized double severity() {
        return lastSeverity;
    }

    public synchronized double speedRatio() {
        return speedRatio;
    }

    public synchronized long sequenceNumber() {
        return sequenceNumber;
    }
}
