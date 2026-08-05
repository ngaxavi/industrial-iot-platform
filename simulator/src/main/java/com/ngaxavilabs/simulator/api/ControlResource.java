package com.ngaxavilabs.simulator.api;

import com.ngaxavilabs.simulator.model.Scenario;
import com.ngaxavilabs.simulator.mqtt.DeliveryFaults;
import com.ngaxavilabs.simulator.mqtt.TelemetryPublisher;
import com.ngaxavilabs.simulator.sim.PumpSimulator;
import com.ngaxavilabs.simulator.sim.SimulationEngine;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Runtime control of the fleet, so integration tests and demos can drive the
 * simulator without a restart: start and stop pumps, switch scenarios mid-run,
 * change the VFD setpoint, and dial transport faults up or down.
 */
@Path("/api/simulator")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ControlResource {

    @Inject
    SimulationEngine engine;

    @Inject
    TelemetryPublisher publisher;

    @GET
    @Path("/pumps")
    public List<ControlDtos.PumpView> pumps() {
        return engine.pumps().stream().map(ControlResource::toView).toList();
    }

    @GET
    @Path("/pumps/{equipmentId}")
    public ControlDtos.PumpView pump(@PathParam("equipmentId") String equipmentId) {
        return toView(require(equipmentId));
    }

    @POST
    @Path("/pumps/{equipmentId}/start")
    public ControlDtos.Ack start(@PathParam("equipmentId") String equipmentId) {
        PumpSimulator sim = require(equipmentId);
        sim.start();
        return ack(sim, "start");
    }

    @POST
    @Path("/pumps/{equipmentId}/stop")
    public ControlDtos.Ack stop(@PathParam("equipmentId") String equipmentId) {
        PumpSimulator sim = require(equipmentId);
        sim.stop();
        return ack(sim, "stop");
    }

    /** Clears a latched fault and returns the pump to OFF on the NORMAL scenario. */
    @POST
    @Path("/pumps/{equipmentId}/reset")
    public ControlDtos.Ack reset(@PathParam("equipmentId") String equipmentId) {
        PumpSimulator sim = require(equipmentId);
        sim.reset();
        return ack(sim, "reset");
    }

    @PUT
    @Path("/pumps/{equipmentId}/scenario")
    public ControlDtos.Ack scenario(@PathParam("equipmentId") String equipmentId,
                                    ControlDtos.ScenarioRequest request) {
        if (request == null || request.scenario() == null) {
            throw new WebApplicationException("scenario is required", Response.Status.BAD_REQUEST);
        }
        PumpSimulator sim = require(equipmentId);
        // rampSeconds is optional: null keeps the pump's configured ramp.
        sim.setScenario(request.scenario(), request.rampSeconds());
        return ack(sim, "scenario=" + request.scenario());
    }

    @PUT
    @Path("/pumps/{equipmentId}/speed")
    public ControlDtos.Ack speed(@PathParam("equipmentId") String equipmentId,
                                 ControlDtos.SpeedRequest request) {
        if (request == null) {
            throw new WebApplicationException("speedRatio is required", Response.Status.BAD_REQUEST);
        }
        PumpSimulator sim = require(equipmentId);
        sim.setSpeedRatio(request.speedRatio());
        return ack(sim, "speedRatio=" + sim.speedRatio());
    }

    /** Applies a scenario to every pump at once — useful for fleet-wide load tests. */
    @PUT
    @Path("/pumps/scenario")
    public List<ControlDtos.PumpView> scenarioAll(ControlDtos.ScenarioRequest request) {
        if (request == null || request.scenario() == null) {
            throw new WebApplicationException("scenario is required", Response.Status.BAD_REQUEST);
        }
        Scenario target = request.scenario();
        engine.pumps().forEach(sim -> sim.setScenario(target, request.rampSeconds()));
        return pumps();
    }

    // --- transport, independent of pump condition (section 6) ---------------

    @GET
    @Path("/delivery-faults")
    public DeliveryFaults deliveryFaults() {
        return publisher.faults();
    }

    @PUT
    @Path("/delivery-faults")
    public DeliveryFaults setDeliveryFaults(ControlDtos.DeliveryFaultsRequest request) {
        if (request == null) {
            throw new WebApplicationException("body is required", Response.Status.BAD_REQUEST);
        }
        DeliveryFaults current = publisher.faults();
        DeliveryFaults next = new DeliveryFaults(
                orDefault(request.duplicateProbability(), current.duplicateProbability()),
                orDefault(request.lateProbability(), current.lateProbability()),
                request.lateDelayMs() != null ? request.lateDelayMs() : current.lateDelayMs(),
                orDefault(request.outOfOrderProbability(), current.outOfOrderProbability()),
                orDefault(request.dropProbability(), current.dropProbability()));
        publisher.setFaults(next);
        return next;
    }

    @GET
    @Path("/stats")
    public Map<String, Long> stats() {
        return publisher.stats();
    }

    private PumpSimulator require(String equipmentId) {
        return engine.pump(equipmentId)
                .orElseThrow(() -> new WebApplicationException(
                        "Unknown pump: " + equipmentId, Response.Status.NOT_FOUND));
    }

    private static ControlDtos.Ack ack(PumpSimulator sim, String action) {
        return new ControlDtos.Ack(sim.equipmentId(), action, sim.runState(), sim.scenario());
    }

    private static double orDefault(Double value, double fallback) {
        return value != null ? value : fallback;
    }

    private static ControlDtos.PumpView toView(PumpSimulator sim) {
        return new ControlDtos.PumpView(
                sim.equipmentId(),
                sim.spec().tenantId(),
                sim.spec().siteId(),
                sim.spec().telemetryTopic(),
                sim.runState(),
                sim.scenario(),
                sim.severity(),
                sim.scenarioElapsedSeconds(),
                sim.scenarioRampSeconds(),
                sim.speedRatio(),
                sim.sequenceNumber(),
                sim.spec().sampleIntervalMs());
    }
}
