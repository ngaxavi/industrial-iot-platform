package com.ngaxavilabs.simulator.config;

import com.ngaxavilabs.simulator.model.PumpType;
import com.ngaxavilabs.simulator.model.Scenario;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;

/**
 * Fleet definition. The map key is the {@code equipmentId}, so adding a pump is
 * one YAML block. Every field has a default so a pump can be declared with only
 * the values that differ from a stock 7.5 kW centrifugal unit.
 */
@ConfigMapping(prefix = "simulator")
public interface SimulatorConfig {

    /** Master switch — off means no ticking and no publishing. */
    @WithDefault("true")
    boolean enabled();

    /** Master seed. Each pump derives its own stream from seed + equipmentId. */
    @WithDefault("42")
    long seed();

    @WithDefault("1.0.0")
    String schemaVersion();

    @WithDefault("25.0")
    double ambientTemperatureC();

    Delivery delivery();

    Map<String, PumpDef> pumps();

    interface PumpDef {

        @WithDefault("tenant-a")
        String tenantId();

        @WithDefault("plant-01")
        String siteId();

        @WithDefault("CENTRIFUGAL")
        PumpType type();

        /** Rated flow, m3/h. */
        @WithDefault("50")
        double ratedFlowM3h();

        /** Best-efficiency-point flow, m3/h. */
        @WithDefault("45")
        double bepFlowM3h();

        /** Rated head, m. */
        @WithDefault("40")
        double ratedHeadM();

        /** Rated speed, rpm — the VFD reference. */
        @WithDefault("1450")
        double ratedSpeedRpm();

        /** Rated electrical power, kW. */
        @WithDefault("7.5")
        double ratedPowerKw();

        /** Commissioning baseline: suction pressure, bar. */
        @WithDefault("1.3")
        double suctionPressureBar();

        /** Commissioning baseline: vibration RMS, mm/s. */
        @WithDefault("1.8")
        double baselineVibrationMmS();

        /** Commissioning baseline: bearing temperature at full load, degC. */
        @WithDefault("58")
        double baselineBearingTempC();

        /** Static lift as a fraction of rated head; the rest is friction. */
        @WithDefault("0.30")
        double staticHeadFraction();

        /** Sample interval, ms. 1 Hz per the minimum viable simulator. */
        @WithDefault("1000")
        long sampleIntervalMs();

        /** Firmware/schema version carried for schema-evolution tests. */
        @WithDefault("fw-2.4.1")
        String firmwareVersion();

        /** Speed setpoint as a fraction of rated speed. */
        @WithDefault("1.0")
        double speedRatio();

        @WithDefault("NORMAL")
        Scenario scenario();

        /** Seconds for the active scenario to reach full severity. */
        @WithDefault("1800")
        double scenarioRampSeconds();

        @WithDefault("true")
        boolean autoStart();
    }

    /**
     * Section 6: transport faults, deliberately independent of pump condition.
     * A perfectly healthy pump can still deliver duplicates and late events.
     */
    interface Delivery {

        /** Probability a message is published twice with the same messageId. */
        @WithDefault("0.0")
        double duplicateProbability();

        /** Probability a message is held back and published later. */
        @WithDefault("0.0")
        double lateProbability();

        /** How long a late message is held, ms. */
        @WithDefault("5000")
        long lateDelayMs();

        /** Probability a message is swapped with the following one. */
        @WithDefault("0.0")
        double outOfOrderProbability();

        /** Probability a message is silently dropped. */
        @WithDefault("0.0")
        double dropProbability();
    }
}
