package com.ngaxavilabs.simulator.mqtt;

/**
 * Section 6: transport behaviour, held separately from pump condition.
 *
 * <p>These probabilities describe the delivery path, not the machine. A pump in
 * perfect health can produce duplicates and out-of-order events, and a failing
 * pump can deliver flawlessly — keeping the two independent is what lets you
 * test deduplication and event-time handling without confounding the physics.
 */
public record DeliveryFaults(
        double duplicateProbability,
        double lateProbability,
        long lateDelayMs,
        double outOfOrderProbability,
        double dropProbability) {

    public static DeliveryFaults none() {
        return new DeliveryFaults(0, 0, 5000, 0, 0);
    }

    public DeliveryFaults {
        duplicateProbability = clampProbability(duplicateProbability);
        lateProbability = clampProbability(lateProbability);
        outOfOrderProbability = clampProbability(outOfOrderProbability);
        dropProbability = clampProbability(dropProbability);
        lateDelayMs = Math.max(0, lateDelayMs);
    }

    public boolean isClean() {
        return duplicateProbability == 0 && lateProbability == 0
                && outOfOrderProbability == 0 && dropProbability == 0;
    }

    private static double clampProbability(double p) {
        return Math.max(0.0, Math.min(1.0, p));
    }
}
