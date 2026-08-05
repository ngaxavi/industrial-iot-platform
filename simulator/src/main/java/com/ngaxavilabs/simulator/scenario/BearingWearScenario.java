package com.ngaxavilabs.simulator.scenario;

import com.ngaxavilabs.simulator.model.PumpSpec;
import com.ngaxavilabs.simulator.model.Scenario;
import com.ngaxavilabs.simulator.physics.Modifiers;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Random;

/**
 * Gradual bearing degradation: vibration climbs steadily, bearing temperature
 * follows, and friction eats a few points of efficiency. The signal is slow and
 * monotonic — the case trending alerts are supposed to catch early.
 */
@ApplicationScoped
public class BearingWearScenario implements ScenarioModel {

    private static final double MAX_VIBRATION_ADD = 7.0;   // mm/s on top of baseline
    private static final double MAX_BEARING_RISE = 38.0;   // degC
    private static final double MAX_EFFICIENCY_LOSS = 0.09;

    @Override
    public Scenario id() {
        return Scenario.BEARING_WEAR;
    }

    @Override
    public void apply(Modifiers mods, PumpSpec spec, double elapsedSeconds, double rampSeconds, Random rng) {
        double p = progress(elapsedSeconds, rampSeconds);
        // Wear accelerates: damage begets damage.
        double curved = p * p * 0.65 + p * 0.35;

        mods.suctionPressureBar = spec.baselineSuctionPressureBar() + 0.02 * Math.sin(elapsedSeconds / 90.0);
        mods.vibrationAddMmS = MAX_VIBRATION_ADD * curved;
        mods.vibrationNoiseFactor = 1.0 + 1.5 * curved;
        mods.bearingTempAddC = MAX_BEARING_RISE * curved;
        mods.efficiencyFactor = 1.0 - MAX_EFFICIENCY_LOSS * curved;
        mods.severity = curved;
    }
}
