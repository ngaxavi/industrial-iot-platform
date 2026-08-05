package com.ngaxavilabs.simulator.model;

/**
 * Section 2 raw telemetry. One correlated snapshot: every field here comes out
 * of the same physics step, so the values are consistent with each other.
 * Field names match the event contract in section 7 verbatim.
 */
public record Measurements(
        double flowRateM3h,
        double suctionPressureBar,
        double dischargePressureBar,
        double motorSpeedRpm,
        double electricalPowerKw,
        double motorCurrentA,
        double bearingTemperatureDeC,
        double bearingTemperatureNdeC,
        double vibrationRmsMmS,
        double casingTemperatureC) {
}
