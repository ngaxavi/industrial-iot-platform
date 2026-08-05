package com.ngaxavilabs.simulator.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ngaxavilabs.simulator.model.TelemetryEvent;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.reactive.messaging.mqtt.MqttMessage;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Random;

/**
 * Publishes telemetry to MQTT at QoS 1, one topic per asset:
 * {@code tenant/{tenantId}/site/{siteId}/equipment/{equipmentId}/telemetry}.
 *
 * <p>Delivery faults are applied here and nowhere else. That separation is
 * deliberate (section 6): the payload is already final and physically
 * consistent by the time it reaches this class, so anything that goes wrong
 * from here on is a transport problem the ingestion service must handle —
 * deduplication by {@code messageId}, ordering by {@code sequenceNumber},
 * watermarking by {@code eventTime}.
 */
@ApplicationScoped
public class TelemetryPublisher {

    private static final Logger LOG = Logger.getLogger(TelemetryPublisher.class);

    @Inject
    @Channel("pump-telemetry")
    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 20_000)
    Emitter<byte[]> emitter;

    @Inject
    ObjectMapper objectMapper;

    private final AtomicReference<DeliveryFaults> faults = new AtomicReference<>(DeliveryFaults.none());
    /** One held-back message per pump, released when the next one arrives. */
    private final Map<String, Pending> heldBack = new ConcurrentHashMap<>();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong duplicated = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();
    private final AtomicLong reordered = new AtomicLong();

    private final Random rng = new Random(7_919L);
    private ScheduledExecutorService delayScheduler;

    private record Pending(String topic, byte[] payload) {
    }

    @PostConstruct
    void init() {
        delayScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "mqtt-late-delivery");
            t.setDaemon(true);
            return t;
        });
    }

    void onShutdown(@Observes ShutdownEvent event) {
        if (delayScheduler != null) {
            delayScheduler.shutdownNow();
        }
    }

    public void publish(String topic, TelemetryEvent event) {
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to serialise telemetry for %s", event.equipmentId());
            return;
        }

        DeliveryFaults f = faults.get();
        String key = event.equipmentId();

        // Release anything held back from the previous tick first, so the
        // reordering is visible as a genuine sequence inversion downstream.
        Pending previouslyHeld = heldBack.remove(key);

        if (f.isClean()) {
            if (previouslyHeld != null) {
                send(previouslyHeld.topic(), previouslyHeld.payload());
            }
            send(topic, payload);
            return;
        }

        boolean drop = roll(f.dropProbability());
        boolean duplicate = roll(f.duplicateProbability());
        boolean late = roll(f.lateProbability());
        boolean outOfOrder = !late && !drop && roll(f.outOfOrderProbability());

        if (outOfOrder) {
            // Hold this one back; the next tick's message overtakes it.
            heldBack.put(key, new Pending(topic, payload));
            reordered.incrementAndGet();
        }

        if (previouslyHeld != null) {
            send(previouslyHeld.topic(), previouslyHeld.payload());
        }

        if (outOfOrder) {
            return;
        }

        if (drop) {
            dropped.incrementAndGet();
            return;
        }

        if (late) {
            delayed.incrementAndGet();
            byte[] deferred = payload;
            delayScheduler.schedule(() -> send(topic, deferred), f.lateDelayMs(), TimeUnit.MILLISECONDS);
        } else {
            send(topic, payload);
        }

        if (duplicate) {
            // Same messageId — this is what the deduplication path must absorb.
            duplicated.incrementAndGet();
            send(topic, payload);
        }
    }

    private void send(String topic, byte[] payload) {
        try {
            emitter.send(MqttMessage.of(topic, payload, MqttQoS.AT_LEAST_ONCE));
            published.incrementAndGet();
        } catch (RuntimeException e) {
            LOG.warnf("MQTT publish failed for topic %s: %s", topic, e.getMessage());
        }
    }

    private boolean roll(double probability) {
        return probability > 0 && rng.nextDouble() < probability;
    }

    public void setFaults(DeliveryFaults next) {
        faults.set(next);
        LOG.infof("Delivery faults updated: %s", next);
    }

    public DeliveryFaults faults() {
        return faults.get();
    }

    public Map<String, Long> stats() {
        return Map.of(
                "published", published.get(),
                "dropped", dropped.get(),
                "duplicated", duplicated.get(),
                "delayed", delayed.get(),
                "reordered", reordered.get());
    }
}
