package com.ngaxavilabs.simulator.scenario;

import com.ngaxavilabs.simulator.model.PumpSpec;
import com.ngaxavilabs.simulator.model.Scenario;
import com.ngaxavilabs.simulator.physics.Modifiers;

import java.util.Random;

/**
 * A degradation pattern from section 5. Implementations describe how operating
 * conditions drift over time; they never write measurements themselves.
 *
 * <p>Adding blocked discharge, worn impeller, motor overload, VFD fault or short
 * cycling means adding one class here — no change to the physics or transport.
 */
public interface ScenarioModel {

    Scenario id();

    /**
     * @param mods           modifiers to mutate, pre-seeded with baseline values
     * @param spec           the asset profile
     * @param elapsedSeconds time since this scenario became active on this pump
     * @param rampSeconds    time the fault takes to reach full severity
     * @param rng            the pump's seeded generator
     */
    void apply(Modifiers mods, PumpSpec spec, double elapsedSeconds, double rampSeconds, Random rng);

    /** Linear 0..1 progression, clamped. */
    default double progress(double elapsedSeconds, double rampSeconds) {
        if (rampSeconds <= 0) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, elapsedSeconds / rampSeconds));
    }
}
