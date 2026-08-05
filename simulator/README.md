# Pump Simulator

Quarkus service that generates credible centrifugal-pump telemetry and publishes it
over MQTT to the ingestion service. Built against `pump-simulator-criteria.md`.

It is not a digital twin. It exists so ingestion, Kafka, TimescaleDB and alerting
can be tested against data that behaves like a real pump — correlated, stateful,
reproducible, and occasionally delivered badly on purpose.

## Quick start

```shell
docker compose up -d          # local Mosquitto broker on 1883
./gradlew quarkusDev          # simulator on 8081
```

Watch the stream:

```shell
mosquitto_sub -h localhost -t 'tenant/#' -v
```

Point it at another broker with `MQTT_HOST` / `MQTT_PORT`.

## What it publishes

One snapshot per pump per sample interval (1 Hz by default), QoS 1, on a per-asset topic:

```
tenant/{tenantId}/site/{siteId}/equipment/{equipmentId}/telemetry
```

```json
{
  "schemaVersion": "1.0.0",
  "messageId": "9f1c...",
  "tenantId": "tenant-a",
  "siteId": "plant-01",
  "equipmentId": "pump-001",
  "eventTime": "2026-08-02T10:15:30Z",
  "generatedAt": "2026-08-02T10:15:30.010Z",
  "sequenceNumber": 421,
  "scenario": "BEARING_WEAR",
  "runState": "DEGRADING",
  "quality": "GOOD",
  "measurements": {
    "flowRateM3h": 50.0,
    "suctionPressureBar": 1.316,
    "dischargePressureBar": 5.233,
    "motorSpeedRpm": 1450.0,
    "electricalPowerKw": 7.876,
    "motorCurrentA": 13.374,
    "bearingTemperatureDeC": 56.5,
    "bearingTemperatureNdeC": 52.0,
    "vibrationRmsMmS": 6.46,
    "casingTemperatureC": 37.6
  }
}
```

`messageId` is the deduplication key. `sequenceNumber` is monotonic per pump.
`eventTime` is when the sample was taken; `generatedAt` is when it was serialised.
Ingestion and persistence timestamps belong downstream.

## How the numbers stay correlated

Nothing is independently random. Every sample solves one operating point and
derives the rest from it:

```
pump curve    H(Q) = s^2 * H_shutoff - k * Q^2
system curve  H(Q) = H_static + R * Q^2
intersection  ->  Q, then H

dP     = rho * g * H                        -> discharge = suction + dP
P_hyd  = rho * g * Q * H                    -> hydraulic power
P_elec = P_hyd / (eta_pump * eta_motor)     -> electrical power
I      = P_elec / (sqrt(3) * V * cos phi)   -> motor current
```

Affinity behaviour (flow ~ speed, head ~ speed^2, power ~ speed^3) falls out of
that algebra rather than being applied as separate multipliers. Note that it
only holds exactly on a friction-only system — with static lift, halving the
speed costs far more than half the flow. That is correct, and there is a test
pinning it.

Efficiency is a parabola peaking at the best efficiency point. Temperatures lag
the load through a first-order filter, so warm-up and cool-down are visible.

## Scenarios

Set per pump in `application.yaml` or switched at runtime through the API.

| Scenario | Signature |
|---|---|
| `NORMAL` | stable flow and pressure, vibration and temperature near baseline |
| `BEARING_WEAR` | vibration climbs monotonically, bearing temperature follows, efficiency drifts down |
| `CAVITATION` | suction pressure falls toward vapour pressure, flow and pressure go unstable, vibration spikes |
| `DRY_RUN` | flow collapses to zero, casing temperature climbs sharply, fast trip |
| `FAULTED` | immediate latched trip with coast-down |

Health state is derived from the scenario's severity signal, so the state a pump
reports is always consistent with the numbers it is sending:

```
OFF -> STARTING -> NORMAL -> DEGRADING -> CRITICAL -> FAULTED
```

A pump in `OFF` does not degrade — scenario time only advances while the shaft turns.

## Delivery faults are separate from pump faults

Transport behaviour is applied in `TelemetryPublisher`, after the payload is
final. A perfectly healthy pump can produce duplicates and out-of-order events,
and a failing pump can deliver flawlessly. Keeping them independent is what lets
you test deduplication and event-time handling without confounding the physics.

```shell
curl -X PUT localhost:8081/api/simulator/delivery-faults \
  -H 'content-type: application/json' \
  -d '{"duplicateProbability":0.05,"outOfOrderProbability":0.03,"lateProbability":0.02,"lateDelayMs":8000}'
```

- **duplicate** — same `messageId` published twice
- **late** — held and published after `lateDelayMs`
- **out-of-order** — held back so the next sample overtakes it
- **drop** — silently discarded

## Control API

Port 8081.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/simulator/pumps` | fleet state |
| `GET` | `/api/simulator/pumps/{id}` | one pump |
| `POST` | `/api/simulator/pumps/{id}/start` | start |
| `POST` | `/api/simulator/pumps/{id}/stop` | stop |
| `POST` | `/api/simulator/pumps/{id}/reset` | clear a latched fault |
| `PUT` | `/api/simulator/pumps/{id}/scenario` | switch scenario |
| `PUT` | `/api/simulator/pumps/{id}/speed` | VFD setpoint as a fraction of rated |
| `PUT` | `/api/simulator/pumps/scenario` | apply a scenario fleet-wide |
| `GET` / `PUT` | `/api/simulator/delivery-faults` | transport fault probabilities |
| `GET` | `/api/simulator/stats` | published / dropped / duplicated / delayed / reordered |

```shell
curl -X PUT localhost:8081/api/simulator/pumps/pump-001/scenario \
  -H 'content-type: application/json' \
  -d '{"scenario":"CAVITATION","rampSeconds":300}'
```

## Configuration

Fleet lives under `simulator.pumps` in `src/main/resources/application.yaml`.
The map key is the `equipmentId`; every field defaults, so a pump only declares
what differs from a stock 7.5 kW unit.

```yaml
simulator:
  seed: 42                    # same seed replays the same run
  ambient-temperature-c: 25.0
  pumps:
    pump-004:
      tenant-id: tenant-b
      rated-flow-m3h: 90
      scenario: DRY_RUN
      scenario-ramp-seconds: 300
```

Randomness is seeded per pump from `simulator.seed` + `equipmentId`, so a given
configuration replays identically. `messageId` is deliberately *not*
reproducible — it is a delivery identity, not a measurement.

## Reproducibility caveat

Measurement values replay identically for a given seed. Wall-clock timestamps,
`messageId`, and scheduler jitter do not. Assertions should key off
`sequenceNumber` and measurement values, not timestamps.

## Not modelled

CFD, impeller geometry, motor electrodynamics, full NPSH curves, ISO 10816
vibration diagnosis, ML prediction. Section 8 of the criteria, deliberately.

## Next steps

Both extend through existing seams without touching the physics:

- **Remaining scenarios** — blocked discharge, worn impeller, motor overload,
  VFD fault, short cycling. One class each implementing `ScenarioModel`.
- **Sensor faults** — stuck values, drift, spikes, nulls, out-of-range, negative
  flow, stale values, unit mismatch. These belong in a decorator between
  `PumpSimulator` and `TelemetryPublisher`, and would drive the `quality` field,
  which currently always reports `GOOD`.

## Tests

```shell
./gradlew test
```

`PumpPhysicsTest` asserts the correlations (affinity laws, efficiency peak,
throttling response, churn heating). `PumpSimulatorTest` covers the state
machine, scenario progression, sequence integrity and seeded reproducibility.
