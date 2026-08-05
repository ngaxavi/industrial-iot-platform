package com.ngaxavilabs.simulator;

import com.ngaxavilabs.simulator.model.Measurements;
import com.ngaxavilabs.simulator.model.PumpSpec;
import com.ngaxavilabs.simulator.model.PumpType;
import com.ngaxavilabs.simulator.physics.Modifiers;
import com.ngaxavilabs.simulator.physics.PumpPhysics;
import com.ngaxavilabs.simulator.physics.ThermalState;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The physics is only useful if the numbers stay correlated. These tests assert
 * the relationships from section 3 rather than exact values.
 */
class PumpPhysicsTest {

    private static final double AMBIENT = 25.0;

    private static PumpSpec spec() {
        return spec(0.30);
    }

    private static PumpSpec spec(double staticHeadFraction) {
        return new PumpSpec("tenant-a", "plant-01", "pump-001", PumpType.CENTRIFUGAL,
                50, 45, 40, 1450, 7.5,
                1.3, 1.8, 58, staticHeadFraction, 1000, "fw-2.4.1");
    }

    private static Measurements settle(PumpSpec spec, double speedRpm, Modifiers mods, int seconds) {
        PumpPhysics physics = new PumpPhysics(AMBIENT);
        ThermalState thermal = new ThermalState(AMBIENT);
        Random rng = new Random(1);
        Measurements last = null;
        for (int i = 0; i < seconds; i++) {
            last = physics.step(spec, speedRpm, true, mods, thermal, 1.0, rng);
        }
        return last;
    }

    @Test
    void ratedPointIsPhysicallyPlausible() {
        PumpSpec spec = spec();
        Measurements m = settle(spec, 1450, new Modifiers(1.3), 5);

        // Operating point should land near the rated duty point.
        assertThat(m.flowRateM3h()).isBetween(40.0, 60.0);
        // dP = rho*g*H -> ~3.9 bar for 40 m of head.
        assertThat(m.dischargePressureBar() - m.suctionPressureBar()).isBetween(3.0, 4.5);
        // Electrical power must be in the same neighbourhood as the rating.
        assertThat(m.electricalPowerKw()).isBetween(4.0, 9.0);
        // I = P / (sqrt(3) * V * cos phi)
        double expectedCurrent = m.electricalPowerKw() * 1000 / (Math.sqrt(3) * 400 * 0.85);
        assertThat(m.motorCurrentA()).isCloseTo(expectedCurrent, org.assertj.core.data.Offset.offset(0.6));
    }

    @Test
    void affinityLawsHoldOnAPurelyFrictionSystem() {
        // Affinity laws describe the pump, not the installation. On a system
        // with no static lift the operating point tracks them exactly, so this
        // is where they can be asserted tightly.
        PumpSpec spec = spec(0.0);
        Measurements full = settle(spec, 1450, new Modifiers(1.3), 5);
        Measurements half = settle(spec, 725, new Modifiers(1.3), 5);

        double flowRatio = half.flowRateM3h() / full.flowRateM3h();
        double headRatio = (half.dischargePressureBar() - half.suctionPressureBar())
                / (full.dischargePressureBar() - full.suctionPressureBar());
        double hydraulicRatio = flowRatio * headRatio;

        var tolerance = org.assertj.core.data.Offset.offset(0.03);
        assertThat(flowRatio).isCloseTo(0.5, tolerance);          // flow ~ speed
        assertThat(headRatio).isCloseTo(0.25, tolerance);         // head ~ speed^2
        assertThat(hydraulicRatio).isCloseTo(0.125, tolerance);   // power ~ speed^3
    }

    @Test
    void staticLiftFlattensTheFlowResponseToSpeed() {
        // With static head the pump must first overcome the lift, so halving
        // speed costs far more than half the flow. Worth pinning down: it is a
        // common source of "why doesn't the data match the affinity laws".
        PumpSpec spec = spec(0.30);
        Measurements full = settle(spec, 1450, new Modifiers(1.3), 5);
        Measurements half = settle(spec, 725, new Modifiers(1.3), 5);

        assertThat(half.flowRateM3h() / full.flowRateM3h()).isLessThan(0.5);
    }

    @Test
    void efficiencyPeaksAtBestEfficiencyPoint() {
        double atBep = PumpPhysics.efficiencyAt(45, 45);
        double below = PumpPhysics.efficiencyAt(25, 45);
        double above = PumpPhysics.efficiencyAt(70, 45);

        assertThat(atBep).isGreaterThan(below);
        assertThat(atBep).isGreaterThan(above);
        assertThat(below).isGreaterThan(0.0);
    }

    @Test
    void throttlingDropsFlowAndRaisesHead() {
        PumpSpec spec = spec();
        Measurements open = settle(spec, 1450, new Modifiers(1.3), 5);

        Modifiers throttled = new Modifiers(1.3);
        throttled.systemResistanceFactor = 8.0;
        Measurements closed = settle(spec, 1450, throttled, 5);

        assertThat(closed.flowRateM3h()).isLessThan(open.flowRateM3h());
        assertThat(closed.dischargePressureBar()).isGreaterThan(open.dischargePressureBar());
    }

    @Test
    void lowFlowDrivesCasingTemperatureUp() {
        PumpSpec spec = spec();
        Measurements normal = settle(spec, 1450, new Modifiers(1.3), 900);

        Modifiers dry = new Modifiers(0.05);
        dry.flowFactor = 0.0;
        Measurements dryRun = settle(spec, 1450, dry, 900);

        assertThat(dryRun.flowRateM3h()).isLessThan(1.0);
        assertThat(dryRun.casingTemperatureC()).isGreaterThan(normal.casingTemperatureC() + 20);
    }

    @Test
    void stoppedPumpProducesNoFlowOrPower() {
        PumpSpec spec = spec();
        PumpPhysics physics = new PumpPhysics(AMBIENT);
        ThermalState thermal = new ThermalState(AMBIENT);
        Measurements m = physics.step(spec, 0, false, new Modifiers(1.3), thermal, 1.0, new Random(1));

        assertThat(m.flowRateM3h()).isZero();
        assertThat(m.electricalPowerKw()).isZero();
        assertThat(m.motorSpeedRpm()).isZero();
        // A stopped pump still sees line pressure on the suction side.
        assertThat(m.suctionPressureBar()).isGreaterThan(0.0);
    }
}
