package com.ngaxavilabs.simulator.physics;

/**
 * Temperatures have memory: they lag the load rather than tracking it
 * instantaneously. Holding them outside the pure physics function keeps the
 * step deterministic and makes warm-up / cool-down behaviour visible to
 * downstream alerting rules.
 */
public final class ThermalState {

    public double bearingDeC;
    public double bearingNdeC;
    public double casingC;

    public ThermalState(double ambientC) {
        this.bearingDeC = ambientC;
        this.bearingNdeC = ambientC;
        this.casingC = ambientC;
    }

    /** First-order lag toward {@code target} with time constant {@code tauSeconds}. */
    static double approach(double current, double target, double dtSeconds, double tauSeconds) {
        double alpha = 1.0 - Math.exp(-dtSeconds / tauSeconds);
        return current + (target - current) * alpha;
    }
}
