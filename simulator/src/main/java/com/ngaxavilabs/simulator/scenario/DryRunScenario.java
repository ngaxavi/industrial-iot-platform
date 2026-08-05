package com.ngaxavilabs.simulator.scenario;

import com.ngaxavilabs.simulator.model.PumpSpec;
import com.ngaxavilabs.simulator.model.Scenario;
import com.ngaxavilabs.simulator.physics.Modifiers;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Random;

/**
 * Loss of prime: the pump runs without liquid. Flow collapses toward zero and
 * casing temperature climbs sharply because there is nothing to carry heat away.
 *
 * <p>The casing rise is mostly emergent — {@code PumpPhysics} adds churn heating
 * whenever a running pump moves almost no flow — so this scenario only has to
 * starve the suction and add the friction component.
 */
@ApplicationScoped
public class DryRunScenario implements ScenarioModel {

    @Override
    public Scenario id() {
        return Scenario.DRY_RUN;
    }

    @Override
    public void apply(Modifiers mods, PumpSpec spec, double elapsedSeconds, double rampSeconds, Random rng) {
        double p = progress(elapsedSeconds, rampSeconds);

        mods.suctionPressureBar = Math.max(0.02, spec.baselineSuctionPressureBar() * (1.0 - p));
        mods.flowFactor = Math.max(0.0, 1.0 - 1.6 * p);
        mods.headFactor = Math.max(0.05, 1.0 - 0.8 * p);
        mods.efficiencyFactor = Math.max(0.3, 1.0 - 0.5 * p);

        // Dry running is a friction event: seals and casing heat fast.
        mods.casingTempAddC = 45.0 * p;
        mods.bearingTempAddC = 15.0 * p;
        mods.vibrationAddMmS = 2.0 * p;
        mods.vibrationNoiseFactor = 1.0 + 3.0 * p;

        // Fast progression to trip — dry running destroys a pump in minutes.
        mods.severity = Math.min(1.0, p * 1.15);
    }
}
