package com.ngaxavilabs.simulator.api;

import com.ngaxavilabs.simulator.model.RunState;
import com.ngaxavilabs.simulator.model.Scenario;

/** Request and response payloads for the control API. */
public final class ControlDtos {

    private ControlDtos() {
    }

    public record PumpView(
            String equipmentId,
            String tenantId,
            String siteId,
            String topic,
            RunState runState,
            Scenario scenario,
            double severity,
            double scenarioElapsedSeconds,
            double scenarioRampSeconds,
            double speedRatio,
            long sequenceNumber,
            long sampleIntervalMs) {
    }

    public record ScenarioRequest(Scenario scenario, Double rampSeconds) {
    }

    public record SpeedRequest(double speedRatio) {
    }

    public record DeliveryFaultsRequest(
            Double duplicateProbability,
            Double lateProbability,
            Long lateDelayMs,
            Double outOfOrderProbability,
            Double dropProbability) {
    }

    public record Ack(String equipmentId, String action, RunState runState, Scenario scenario) {
    }
}
