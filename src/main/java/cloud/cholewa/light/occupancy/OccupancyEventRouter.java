package cloud.cholewa.light.occupancy;

import cloud.cholewa.home.model.DeviceEvent;
import cloud.cholewa.home.model.EatonRxEvent;
import cloud.cholewa.light.rule.RuleEngine;
import cloud.cholewa.shelly.codec.ShellyEvent;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import cloud.cholewa.shelly.codec.ShellyTopic;
import cloud.cholewa.shelly.model.BthomeSensorStatus;
import cloud.cholewa.shelly.model.IlluminanceStatus;
import cloud.cholewa.shelly.model.PresenceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

//Which sensor of the occupancy an event of the device queue is about, and what it says (HAS-260). As for
//the lights, the routing key decides first, by the sensors as last read, so the body of an event of
//another device is never read.
//- eaton.<gateway>.<point> of a PIR of the AMX system: switch on is motion, switch off its end (HAS-288)
//- <deviceId>.status.presencezone:<zone> of a Presence: the zone of the sensor - value, num_objects;
//  <deviceId>.status.illuminance:0 its light in a word; <deviceId>.online its place on the broker
//- <gatewayId>.status.bthomesensor:<id> of a component OccupancyReads mapped to a BLU Motion: motion or lux,
//  {id, value, last_updated_ts}
//A motion is handed to the rules of its room (HAS-262) before the event is done - the engine never fails.
//A body that is not what its topic carries is an error for the consumer to log
@Component
@RequiredArgsConstructor
public class OccupancyEventRouter {

    private static final String EATON = "eaton.";
    private static final String ZONE = "presencezone";
    private static final String ILLUMINANCE = "illuminance";
    private static final String BTHOME_SENSOR = "bthomesensor";

    private final OccupancyRegistry occupancyRegistry;
    private final OccupancyTracker occupancyTracker;
    private final OccupancyReads occupancyReads;
    private final RuleEngine ruleEngine;
    private final ShellyMqttDecoder shellyMqttDecoder;
    private final JsonMapper jsonMapper;

    public Mono<Void> route(final String routingKey, final byte[] body) {
        if (routingKey == null) {
            return Mono.empty();
        }
        if (routingKey.startsWith(EATON)) {
            return eaton(routingKey, body);
        }
        return ShellyTopic.parse(routingKey)
            .map(topic -> shelly(topic, body))
            .orElseGet(Mono::empty);
    }

    private Mono<Void> eaton(final String routingKey, final byte[] body) {
        final String[] parts = routingKey.split("\\.");
        final Integer point = parts.length == 3 ? point(parts[2]) : null;
        final OccupancySensor sensor = point == null ? null : occupancyRegistry.sensors().stream()
            .filter(candidate -> candidate.binding() instanceof SensorBinding.EatonMotion eaton
                && eaton.point() == point
                && eaton.gateway().getValue().equals(parts[1]))
            .findFirst()
            .orElse(null);
        if (sensor == null) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> jsonMapper.readValue(body, DeviceEvent.class))
            .filter(event -> event.getEvent() == EatonRxEvent.SWITCH_ON || event.getEvent() == EatonRxEvent.SWITCH_OFF)
            .flatMap(event -> occupancyTracker.motion(sensor, event.getEvent() == EatonRxEvent.SWITCH_ON)
                ? ruleEngine.motion(sensor.room())
                : Mono.empty())
            .then();
    }

    private Mono<Void> shelly(final ShellyTopic topic, final byte[] body) {
        if (topic.kind() == ShellyTopic.Kind.STATUS && BTHOME_SENSOR.equals(topic.component())) {
            return occupancyReads.targetOf(topic.deviceId(), topic.index())
                .map(target -> decode(topic, body)
                    .flatMap(status -> Mono.justOrEmpty(status.as(BthomeSensorStatus.class)))
                    .flatMap(status -> occupancyReads.observe(target, status)
                        ? ruleEngine.motion(target.sensor().room())
                        : Mono.empty())
                    .then())
                .orElseGet(Mono::empty);
        }

        //one device may stand for several sensors, a zone each: its place on the broker and its light are
        //every one's, a zone only its own sensor's
        final List<OccupancySensor> sensors = occupancyRegistry.sensors().stream()
            .filter(candidate -> candidate.binding() instanceof SensorBinding.Presence presence
                && topic.deviceId().equalsIgnoreCase(presence.deviceId()))
            .toList();
        if (sensors.isEmpty()) {
            return Mono.empty();
        }

        return switch (topic.kind()) {
            case ONLINE -> Mono.fromCallable(() -> shellyMqttDecoder.decode(topic, body))
                .flatMap(Mono::justOrEmpty)
                .cast(ShellyEvent.Online.class)
                .doOnNext(online -> sensors.forEach(sensor -> occupancyTracker.online(sensor, online.online())))
                .then();
            case STATUS -> {
                if (ZONE.equals(topic.component()) && topic.index() != null) {
                    final OccupancySensor sensor = sensors.stream()
                        .filter(candidate -> ((SensorBinding.Presence) candidate.binding()).zone() == topic.index())
                        .findFirst()
                        .orElse(null);
                    yield sensor == null ? Mono.empty() : decode(topic, body)
                        .flatMap(status -> Mono.justOrEmpty(status.as(PresenceStatus.class)))
                        .flatMap(status -> Mono.justOrEmpty(Optional.ofNullable(status.getValue())))
                        .doOnNext(occupied -> occupancyTracker.presence(sensor, occupied, true))
                        .then();
                }
                if (ILLUMINANCE.equals(topic.component())) {
                    yield decode(topic, body)
                        .flatMap(status -> Mono.justOrEmpty(status.as(IlluminanceStatus.class)))
                        .doOnNext(status -> sensors.forEach(sensor ->
                            occupancyTracker.illumination(sensor, status.getIllumination(), status.getLux())))
                        .then();
                }
                yield Mono.empty();
            }
            default -> Mono.empty();
        };
    }

    private Mono<ShellyEvent.Status> decode(final ShellyTopic topic, final byte[] body) {
        return Mono.fromCallable(() -> shellyMqttDecoder.decode(topic, body))
            .flatMap(Mono::justOrEmpty)
            .cast(ShellyEvent.Status.class);
    }

    private static Integer point(final String text) {
        return !text.isEmpty() && text.length() <= 2 && text.chars().allMatch(Character::isDigit)
            ? Integer.valueOf(text)
            : null;
    }
}
