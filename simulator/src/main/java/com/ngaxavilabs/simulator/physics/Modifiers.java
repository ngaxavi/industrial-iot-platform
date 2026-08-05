package com.ngaxavilabs.simulator.physics;

/**
 * The single channel through which a scenario perturbs the physics.
 *
 * Scenarios never write measurements directly — they nudge the operating
 * conditions (suction pressure, system resistance, efficiency, added heat and
 * vibration) and let {@link PumpPhysics} recompute a consistent snapshot. That
 * is what keeps flow, pressure, power and current correlated no matter which
 * fault is active.
 */
public final class Modifiers {

    /** Multiplies commanded speed — VFD misbehaviour, trips. */
    public double speedFactor = 1.0;
    /** Multiplies system curve resistance — >1 throttles/blocks discharge. */
    public double systemResistanceFactor = 1.0;
    /** Absolute suction pressure in bar; scenarios starve it to force cavitation/dry run. */
    public double suctionPressureBar;
    /** Multiplies the delivered head — worn impeller, internal recirculation. */
    public double headFactor = 1.0;
    /** Multiplies the solved flow — two-phase flow, loss of prime. */
    public double flowFactor = 1.0;
    /** Multiplies pump efficiency — wear, clearance loss. */
    public double efficiencyFactor = 1.0;
    /** Additive vibration in mm/s on top of the operating-point baseline. */
    public double vibrationAddMmS = 0.0;
    /** Scales the vibration noise band — cavitation makes the signal ragged. */
    public double vibrationNoiseFactor = 1.0;
    /** Additive bearing temperature rise in degC. */
    public double bearingTempAddC = 0.0;
    /** Additive casing temperature rise in degC. */
    public double casingTempAddC = 0.0;
    /** 0..1 fault progression, mapped onto NORMAL/DEGRADING/CRITICAL/FAULTED. */
    public double severity = 0.0;
    /** Forces an immediate trip regardless of severity. */
    public boolean trip = false;

    public Modifiers(double baselineSuctionPressureBar) {
        this.suctionPressureBar = baselineSuctionPressureBar;
    }
}
