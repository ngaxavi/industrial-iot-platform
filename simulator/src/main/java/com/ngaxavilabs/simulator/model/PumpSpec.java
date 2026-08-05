package com.ngaxavilabs.simulator.model;

/**
 * Section 1: the immutable asset profile plus the commissioning baseline.
 * Health is expressed as deviation from these values, never as an absolute.
 */
public record PumpSpec(
        String tenantId,
        String siteId,
        String equipmentId,
        PumpType pumpType,
        double ratedFlowM3h,
        double bepFlowM3h,
        double ratedHeadM,
        double ratedSpeedRpm,
        double ratedPowerKw,
        double baselineSuctionPressureBar,
        double baselineVibrationMmS,
        double baselineBearingTempC,
        double staticHeadFraction,
        long sampleIntervalMs,
        String firmwareVersion) {

    public PumpSpec {
        if (ratedFlowM3h <= 0 || ratedHeadM <= 0 || ratedSpeedRpm <= 0 || ratedPowerKw <= 0) {
            throw new IllegalArgumentException("rated flow/head/speed/power must be positive for " + equipmentId);
        }
        if (bepFlowM3h <= 0) {
            bepFlowM3h = ratedFlowM3h;
        }
    }

    /** MQTT topic for this asset: tenant/{t}/site/{s}/equipment/{e}/telemetry */
    public String telemetryTopic() {
        return "tenant/" + tenantId + "/site/" + siteId + "/equipment/" + equipmentId + "/telemetry";
    }
}
