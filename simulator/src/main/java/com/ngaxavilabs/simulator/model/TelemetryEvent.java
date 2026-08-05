package com.ngaxavilabs.simulator.model;

import java.time.Instant;

/**
 * Section 7 event contract: one snapshot per pump per sample time.
 *
 * <ul>
 *   <li>{@code messageId} — deduplication key, stable across duplicate deliveries.</li>
 *   <li>{@code sequenceNumber} — monotonic per pump, diagnoses ordering.</li>
 *   <li>{@code eventTime} — when the sample was taken (event time).</li>
 *   <li>{@code generatedAt} — when the payload was serialised (processing time).</li>
 * </ul>
 *
 * Ingestion and persistence timestamps are added downstream, not here.
 */
public record TelemetryEvent(
        String schemaVersion,
        String messageId,
        String tenantId,
        String siteId,
        String equipmentId,
        Instant eventTime,
        Instant generatedAt,
        long sequenceNumber,
        Scenario scenario,
        RunState runState,
        Quality quality,
        Measurements measurements) {
}
