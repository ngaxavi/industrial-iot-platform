package com.ngaxavilabs.simulator.scenario;

import com.ngaxavilabs.simulator.model.PumpSpec;
import com.ngaxavilabs.simulator.model.Scenario;
import com.ngaxavilabs.simulator.physics.Modifiers;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Random;

/**
 * Suction pressure falls toward vapour pressure, bubbles form and collapse in
 * the impeller. Signature: low suction pressure, unstable flow and discharge
 * pressure, and vibration that spikes rather than trends.
 *
 * <p>Deliberately not an NPSH model (section 8) — suction pressure is driven
 * down directly and the instability is imposed on top.
 */
@ApplicationScoped
public class CavitationScenario implements ScenarioModel {

    /** Suction pressure floor, near vapour pressure of water at ambient. */
    private static final double SUCTION_FLOOR_BAR = 0.12;
    private static final double SPIKE_PROBABILITY = 0.18;

    @Override
    public Scenario id() {
        return Scenario.CAVITATION;
    }

    @Override
    public void apply(Modifiers mods, PumpSpec spec, double elapsedSeconds, double rampSeconds, Random rng) {
        double p = progress(elapsedSeconds, rampSeconds);
        double base = spec.baselineSuctionPressureBar();

        // Suction starves, with growing oscillation as bubbles form and collapse.
        double oscillation = 0.10 * p * Math.sin(elapsedSeconds * 2.1) + 0.05 * p * Math.sin(elapsedSeconds * 5.3);
        mods.suctionPressureBar = Math.max(SUCTION_FLOOR_BAR, base - (base - SUCTION_FLOOR_BAR) * p + oscillation);

        // Vapour in the eye of the impeller blocks flow and kills head.
        mods.flowFactor = 1.0 - 0.30 * p * (1.0 + 0.35 * Math.sin(elapsedSeconds * 3.7));
        mods.headFactor = 1.0 - 0.22 * p;
        mods.efficiencyFactor = 1.0 - 0.18 * p;

        // Vibration is ragged and spiky, not a clean trend.
        mods.vibrationAddMmS = 3.5 * p;
        mods.vibrationNoiseFactor = 1.0 + 6.0 * p;
        if (rng.nextDouble() < SPIKE_PROBABILITY * p) {
            mods.vibrationAddMmS += 2.0 + rng.nextDouble() * 6.0;
        }

        mods.severity = p * 0.95;
    }
}
