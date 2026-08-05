package com.ngaxavilabs.simulator.model;

/**
 * Section 5 scenarios. The minimum viable simulator implements the five below;
 * the remaining scenarios (blocked discharge, leak, motor overload, VFD fault,
 * short cycling) plug in through the same {@code ScenarioModel} interface.
 */
public enum Scenario {
    NORMAL,
    BEARING_WEAR,
    CAVITATION,
    DRY_RUN,
    FAULTED
}
