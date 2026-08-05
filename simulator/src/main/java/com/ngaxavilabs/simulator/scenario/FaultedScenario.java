package com.ngaxavilabs.simulator.scenario;

import com.ngaxavilabs.simulator.model.PumpSpec;
import com.ngaxavilabs.simulator.model.Scenario;
import com.ngaxavilabs.simulator.physics.Modifiers;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Random;

/**
 * Hard trip. Useful on its own for testing alerting and downstream state
 * handling without waiting for a degradation ramp to play out.
 */
@ApplicationScoped
public class FaultedScenario implements ScenarioModel {

    @Override
    public Scenario id() {
        return Scenario.FAULTED;
    }

    @Override
    public void apply(Modifiers mods, PumpSpec spec, double elapsedSeconds, double rampSeconds, Random rng) {
        mods.suctionPressureBar = spec.baselineSuctionPressureBar();
        mods.severity = 1.0;
        mods.trip = true;
    }
}
