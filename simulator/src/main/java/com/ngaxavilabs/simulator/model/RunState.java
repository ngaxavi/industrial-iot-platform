package com.ngaxavilabs.simulator.model;

/**
 * Section 4 state model.
 *
 * <pre>OFF -> STARTING -> NORMAL -> DEGRADING -> CRITICAL -> FAULTED</pre>
 *
 * MAINTENANCE is intentionally out of scope for v1.
 */
public enum RunState {
    OFF,
    STARTING,
    NORMAL,
    DEGRADING,
    CRITICAL,
    FAULTED;

    /** True when the shaft is expected to be turning. */
    public boolean isRunning() {
        return this == STARTING || this == NORMAL || this == DEGRADING || this == CRITICAL;
    }

    /** Maps a 0..1 severity signal onto the health portion of the state model. */
    public static RunState fromSeverity(double severity) {
        if (severity >= 0.90) {
            return FAULTED;
        }
        if (severity >= 0.60) {
            return CRITICAL;
        }
        if (severity >= 0.25) {
            return DEGRADING;
        }
        return NORMAL;
    }
}
