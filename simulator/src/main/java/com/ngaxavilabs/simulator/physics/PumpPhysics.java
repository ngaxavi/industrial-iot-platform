package com.ngaxavilabs.simulator.physics;

import com.ngaxavilabs.simulator.model.Measurements;
import com.ngaxavilabs.simulator.model.PumpSpec;

import java.util.Random;

/**
 * Section 3: correlated measurements.
 *
 * <p>Nothing here is independently random. Each step solves one operating point
 * and derives every measurement from it:
 *
 * <pre>
 *   pump curve    H_pump(Q) = s^2 * H_shutoff - k * Q^2      (affinity: head ~ speed^2)
 *   system curve  H_sys(Q)  = H_static + R * Q^2
 *   operating pt  H_pump(Q) = H_sys(Q)  ->  Q, then H
 *
 *   dP  = rho * g * H                    -> discharge = suction + dP
 *   P_h = rho * g * Q * H                -> hydraulic power
 *   P_e = P_h / (eta_pump * eta_motor)   -> electrical power
 *   I   = P_e / (sqrt(3) * V * cos phi)  -> motor current
 * </pre>
 *
 * <p>Affinity laws fall out of the curve algebra rather than being applied as
 * separate multipliers: flow tracks speed, head tracks speed squared, and power
 * tracks speed cubed because power is the product of the first two.
 *
 * <p>Explicit non-goal (section 8): this is not CFD. No impeller geometry, no
 * NPSH curve, no motor electrodynamics.
 */
public final class PumpPhysics {

    /** Water density, kg/m^3. */
    public static final double RHO = 998.0;
    /** Gravitational acceleration, m/s^2. */
    public static final double G = 9.81;

    private static final double MOTOR_EFFICIENCY = 0.92;
    private static final double LINE_VOLTAGE = 400.0;
    private static final double POWER_FACTOR = 0.85;
    /** Efficiency at the best efficiency point. */
    private static final double ETA_BEP = 0.80;
    /** How sharply efficiency falls away from BEP. */
    private static final double ETA_FALLOFF = 0.85;
    private static final double ETA_FLOOR = 0.15;
    /** Shutoff head as a multiple of rated head. */
    private static final double SHUTOFF_RATIO = 1.25;
    /** Windage, bearing drag and motor no-load losses, as a fraction of rated power. */
    private static final double NO_LOAD_POWER_FRACTION = 0.05;

    private static final double BEARING_TAU_S = 240.0;
    private static final double CASING_TAU_S = 150.0;
    /** Casing rise at full load with adequate flow, degC above ambient. */
    private static final double CASING_RISE_C = 12.0;
    /** Extra casing rise when the pump churns liquid it cannot move away. */
    private static final double CHURN_RISE_C = 70.0;
    /** Below this fraction of rated flow, churn heating kicks in. */
    private static final double CHURN_FLOW_FRACTION = 0.12;

    private final double ambientC;

    public PumpPhysics(double ambientC) {
        this.ambientC = ambientC;
    }

    /**
     * Advances the thermal state and returns one correlated snapshot.
     *
     * @param speedRpm actual shaft speed after ramping and any VFD modifier
     * @param dtSeconds elapsed time since the previous sample
     */
    public Measurements step(PumpSpec spec, double speedRpm, boolean running, Modifiers mods,
                             ThermalState thermal, double dtSeconds, Random rng) {

        double speed = Math.max(0.0, speedRpm * mods.speedFactor);
        double s = speed / spec.ratedSpeedRpm();

        // --- hydraulics: intersect pump and system curves -------------------
        double shutoffHead = SHUTOFF_RATIO * spec.ratedHeadM();
        double kPump = (shutoffHead - spec.ratedHeadM()) / sq(spec.ratedFlowM3h());
        double staticHead = spec.staticHeadFraction() * spec.ratedHeadM();
        double rSystem = ((spec.ratedHeadM() - staticHead) / sq(spec.ratedFlowM3h()))
                * Math.max(mods.systemResistanceFactor, 1e-3);

        double availableHead = sq(s) * shutoffHead * mods.headFactor;
        double flow = 0.0;
        if (running && availableHead > staticHead) {
            double qSquared = (availableHead - staticHead) / (kPump + rSystem);
            flow = Math.sqrt(Math.max(qSquared, 0.0));
        }
        flow = Math.max(0.0, flow * mods.flowFactor);

        // Read head off the pump curve so a deadheaded pump still reports
        // shutoff head instead of collapsing to the static head.
        double head = Math.max(0.0, availableHead - kPump * sq(flow));

        double deltaPBar = RHO * G * head / 1.0e5;
        double suction = mods.suctionPressureBar;
        double discharge = running ? suction + deltaPBar : suction;

        // --- efficiency and power -------------------------------------------
        double eta = efficiencyAt(flow, spec.bepFlowM3h()) * mods.efficiencyFactor;
        double hydraulicKw = RHO * G * (flow / 3600.0) * head / 1000.0;
        double shaftKw = 0.0;
        if (running) {
            shaftKw = (flow > 1e-3 ? hydraulicKw / eta : 0.0)
                    + NO_LOAD_POWER_FRACTION * spec.ratedPowerKw() * cube(s);
        }
        double electricalKw = shaftKw / MOTOR_EFFICIENCY;
        double currentA = electricalKw * 1000.0 / (Math.sqrt(3.0) * LINE_VOLTAGE * POWER_FACTOR);

        // --- thermal ----------------------------------------------------------
        double loadFactor = clamp(electricalKw / spec.ratedPowerKw(), 0.0, 1.6);
        double bearingRise = spec.baselineBearingTempC() - ambientC;
        double bearingTarget = running
                ? ambientC + bearingRise * (0.35 + 0.65 * loadFactor) + mods.bearingTempAddC
                : ambientC;

        double casingTarget = running
                ? ambientC + CASING_RISE_C * loadFactor + mods.casingTempAddC
                : ambientC;
        // Churn: a running pump moving no liquid dumps shaft power into the casing.
        double churnThreshold = CHURN_FLOW_FRACTION * spec.ratedFlowM3h();
        if (running && flow < churnThreshold) {
            casingTarget += CHURN_RISE_C * (1.0 - flow / churnThreshold) * Math.max(s, 0.2);
        }

        thermal.bearingDeC = ThermalState.approach(thermal.bearingDeC, bearingTarget, dtSeconds, BEARING_TAU_S);
        thermal.bearingNdeC = ThermalState.approach(thermal.bearingNdeC, bearingTarget - 4.5, dtSeconds, BEARING_TAU_S);
        thermal.casingC = ThermalState.approach(thermal.casingC, casingTarget, dtSeconds, CASING_TAU_S);

        // --- vibration ---------------------------------------------------------
        double offBep = spec.bepFlowM3h() > 0 ? Math.abs(flow - spec.bepFlowM3h()) / spec.bepFlowM3h() : 0.0;
        double vibration = running
                ? spec.baselineVibrationMmS() * (0.30 + 0.70 * s) + 2.2 * sq(Math.min(offBep, 1.5)) + mods.vibrationAddMmS
                : 0.05;
        vibration += rng.nextGaussian() * 0.06 * mods.vibrationNoiseFactor * Math.max(vibration, 0.5);

        return new Measurements(
                round(noisy(flow, 0.004, rng), 2),
                round(noisy(suction, 0.006, rng), 3),
                round(noisy(discharge, 0.004, rng), 3),
                round(noisy(speed, 0.002, rng), 1),
                round(noisy(electricalKw, 0.005, rng), 3),
                round(noisy(currentA, 0.005, rng), 2),
                round(noisy(thermal.bearingDeC, 0.002, rng), 2),
                round(noisy(thermal.bearingNdeC, 0.002, rng), 2),
                round(Math.max(vibration, 0.0), 3),
                round(noisy(thermal.casingC, 0.002, rng), 2));
    }

    /**
     * Parabolic efficiency curve peaking at the best efficiency point and
     * falling away on both sides — the "simple efficiency curve" of section 3.
     */
    public static double efficiencyAt(double flowM3h, double bepFlowM3h) {
        if (bepFlowM3h <= 0) {
            return ETA_BEP;
        }
        double x = flowM3h / bepFlowM3h;
        return clamp(ETA_BEP * (1.0 - ETA_FALLOFF * sq(x - 1.0)), ETA_FLOOR, ETA_BEP);
    }

    /** Head a pump would deliver at the given speed ratio, ignoring the system. */
    public static double shutoffHeadAt(PumpSpec spec, double speedRatio) {
        return sq(speedRatio) * SHUTOFF_RATIO * spec.ratedHeadM();
    }

    private static double noisy(double value, double relativeSigma, Random rng) {
        if (value == 0.0) {
            return 0.0;
        }
        return value * (1.0 + rng.nextGaussian() * relativeSigma);
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double cube(double v) {
        return v * v * v;
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
