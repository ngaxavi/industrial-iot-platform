package com.ngaxavilabs.simulator.scenario;

import com.ngaxavilabs.simulator.model.Scenario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves a {@link Scenario} to its model. New scenarios are picked up simply
 * by adding a CDI bean implementing {@link ScenarioModel}.
 */
@ApplicationScoped
public class ScenarioRegistry {

    private final Map<Scenario, ScenarioModel> models = new EnumMap<>(Scenario.class);

    @Inject
    public ScenarioRegistry(Instance<ScenarioModel> discovered) {
        for (ScenarioModel model : discovered) {
            models.put(model.id(), model);
        }
    }

    public ScenarioModel get(Scenario scenario) {
        ScenarioModel model = models.get(scenario);
        if (model == null) {
            throw new IllegalArgumentException("No model registered for scenario " + scenario);
        }
        return model;
    }
}
