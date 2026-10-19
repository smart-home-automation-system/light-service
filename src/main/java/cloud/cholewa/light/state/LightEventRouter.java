package cloud.cholewa.light.state;

import cloud.cholewa.home.model.DeviceEvent;
import cloud.cholewa.home.model.EatonRxEvent;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.light.driver.Gen2Profiles;
import cloud.cholewa.light.driver.Gen2Profiles.ColorComponent;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.model.LightReading;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.shelly.codec.ShellyEvent;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import cloud.cholewa.shelly.codec.ShellyTopic;
import cloud.cholewa.shelly.model.Gen2LightStatus;
import cloud.cholewa.shelly.model.RgbwStatus;
import cloud.cholewa.shelly.model.SwitchStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

//Which light an event of the device queue is about, and what it says. The queue carries every event of
//every device: the routing key alone decides first, by the registry as last read, so the body of an
//event that is not about a light is never read.
//- eaton.<gateway>.<point>: the DeviceEvent of amx-service (HAS-229); only its status is a state - a
//  switch 0 / 1, a dimmer its level. The gateway is the light's own, so a blind on the same point of
//  the other gateway is never taken for it
//- <deviceId>.status.<component>:<channel> and <deviceId>.online of a Gen2+ light, decoded by
//  shelly-client; the component is the one of its kind - switch for a relay, light for a dimmer, rgb
//  or rgbw for an rgbw light. The id matched whatever its case.
//A body that is not what its topic carries is an error for the consumer to log
@Slf4j
@Component
@RequiredArgsConstructor
public class LightEventRouter {

    private static final String EATON = "eaton.";

    private final LightRegistry lightRegistry;
    private final LightStateTracker lightStateTracker;
    private final ShellyMqttDecoder shellyMqttDecoder;
    private final Gen2Profiles gen2Profiles;
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
        final Light light = point == null ? null : lightRegistry.current().stream()
            .filter(candidate -> candidate.binding() instanceof LightBinding.Eaton eaton
                && eaton.point() == point
                && eaton.gateway() != null
                && eaton.gateway().getValue().equals(parts[1]))
            .findFirst()
            .orElse(null);
        if (light == null) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> jsonMapper.readValue(body, DeviceEvent.class))
            .filter(event -> event.getEvent() == EatonRxEvent.STATUS)
            .flatMap(event -> Mono.justOrEmpty(LightReadings.ofEatonStatus(event.getValue(), light.kind()))
                .switchIfEmpty(Mono.fromRunnable(() -> log.warn("Light {} ({}) reported the status {}, which is none of a {}",
                    light.id(), light.name(), event.getValue(), light.kind().getValue()))))
            .flatMap(reading -> lightStateTracker.observe(light, reading));
    }

    private Mono<Void> shelly(final ShellyTopic topic, final byte[] body) {
        final List<Light> lights = lightRegistry.current().stream()
            .filter(light -> light.binding() instanceof LightBinding.ShellyGen2 device
                && topic.deviceId().equalsIgnoreCase(device.deviceId()))
            .toList();
        if (lights.isEmpty()) {
            return Mono.empty();
        }

        return switch (topic.kind()) {
            case ONLINE -> Mono.fromCallable(() -> shellyMqttDecoder.decode(topic, body))
                .flatMap(Mono::justOrEmpty)
                .cast(ShellyEvent.Online.class)
                .flatMapMany(online -> Flux.fromIterable(lights)
                    .concatMap(light -> lightStateTracker.reachability(light, online.online(), "the device left the broker")))
                .then();
            case STATUS -> status(topic, body, lights);
            default -> Mono.empty();
        };
    }

    private Mono<Void> status(final ShellyTopic topic, final byte[] body, final List<Light> lights) {
        final Light light = lights.stream()
            .filter(candidate -> topic.index() != null
                && ((LightBinding.ShellyGen2) candidate.binding()).channel() == topic.index()
                && component(candidate.kind(), topic.component()))
            .findFirst()
            .orElse(null);
        if (light == null) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> shellyMqttDecoder.decode(topic, body))
            .flatMap(Mono::justOrEmpty)
            .cast(ShellyEvent.Status.class)
            .flatMap(status -> Mono.justOrEmpty(reading(light, topic.component(), status)))
            .flatMap(reading -> lightStateTracker.observe(light, reading));
    }

    private Optional<LightReading> reading(final Light light, final String component, final ShellyEvent.Status status) {
        return switch (light.kind()) {
            case RELAY -> status.as(SwitchStatus.class).flatMap(LightReadings::ofSwitch);
            case DIMMER -> status.as(Gen2LightStatus.class).flatMap(LightReadings::ofLight);
            case RGBW -> {
                //the topic names the profile the device is in: learned for the commands at no cost
                gen2Profiles.seen((LightBinding.ShellyGen2) light.binding(),
                    "rgb".equals(component) ? ColorComponent.RGB : ColorComponent.RGBW);
                yield status.as(RgbwStatus.class).flatMap(LightReadings::ofRgbw);
            }
        };
    }

    private static boolean component(final LightKind kind, final String component) {
        return switch (kind) {
            case RELAY -> "switch".equals(component);
            case DIMMER -> "light".equals(component);
            case RGBW -> "rgb".equals(component) || "rgbw".equals(component);
        };
    }

    private static Integer point(final String text) {
        return !text.isEmpty() && text.length() <= 2 && text.chars().allMatch(Character::isDigit)
            ? Integer.valueOf(text)
            : null;
    }
}
