package com.ngaxavilabs.simulator.scenario;

import com.ngaxavilabs.simulator.model.PumpSpec;
import com.ngaxavilabs.simulator.model.Scenario;
import com.ngaxavilabs.simulator.physics.Modifiers;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Random;

/**
 * Healthy operation: stable flow and pressure, vibration and temperature near
 * baseline. Only a slow suction-pressure wander from upstream supply variation.
 */
@ApplicationScoped
public class NormalScenario implements ScenarioModel {

    @Override
    public Scenario id() {
        return Scenario.NORMAL;
    }

    @Override
    public void apply(Modifiers mods, PumpSpec spec, double elapsedSeconds, double rampSeconds, Random rng) {
        double wander = 0.02 * Math.sin(elapsedSeconds / 90.0) + 0.01 * Math.sin(elapsedSeconds / 17.0);
        mods.suctionPressureBar = spec.baselineSuctionPressureBar() + wander;
        mods.severity = 0.0;
    }
}
