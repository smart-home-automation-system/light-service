package cloud.cholewa.light.occupancy;

import cloud.cholewa.light.occupancy.OccupancyRegistry.Gateway;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.BthomeSensorConfig;
import cloud.cholewa.shelly.model.BthomeSensorStatus;
import cloud.cholewa.shelly.model.IlluminanceStatus;
import cloud.cholewa.shelly.model.PresenceStatus;
import cloud.cholewa.shelly.model.ShellyComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

//The reads of the devices behind the occupancy (HAS-260), every five minutes after the registry:
//- every BTHome gateway of the registry, Shelly.GetComponents: which bthomesensor component of it is the
//  motion (object 0x21) or the illuminance (0x05) of which BLU Motion of the registry - the map the status
//  topics are routed by between two reads (the way blinds-service maps its window sensors) - and what
//  each holds now
//- every Presence: its zone (a dynamic component, not in Shelly.GetStatus) and its illuminance:0 - a
//  reconciliation of what MQTT publishes on a change; it never says whether the device is reachable.
//A device that does not answer is a WARN when it starts failing and an INFO when it is back; its sensors
//keep what they said, a BLU one until its gateway has been silent too long
@Slf4j
@Component
public class OccupancyReads {

    //the BTHome objects of a BLU Motion
    static final int MOTION_OBJECT = 0x21;
    static final int ILLUMINANCE_OBJECT = 0x05;
    private static final String SENSOR = "bthomesensor:";
    private static final String ZONE = "presencezone:";
    private static final String ILLUMINANCE = "illuminance:0";

    private final OccupancyRegistry occupancyRegistry;
    private final OccupancyTracker occupancyTracker;
    private final ShellyRpcClient shellyRpcClient;
    //a firmware adds fields to a component, and the models have only what is read here
    private final JsonMapper jsonMapper;

    //which component of which gateway is what of which sensor
    private final Map<GatewayComponent, Target> components = new ConcurrentHashMap<>();
    //the reads that failed last time - a gateway by its address, a presence sensor by its id: Presence Livia
    //is both, on one address
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    public OccupancyReads(
        final OccupancyRegistry occupancyRegistry,
        final OccupancyTracker occupancyTracker,
        final ShellyRpcClient shellyRpcClient,
        final JsonMapper jsonMapper
    ) {
        this.occupancyRegistry = occupancyRegistry;
        this.occupancyTracker = occupancyTracker;
        this.shellyRpcClient = shellyRpcClient;
        this.jsonMapper = jsonMapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    /** A bthomesensor component of a gateway, by the MQTT id of the gateway in lower case. */
    record GatewayComponent(String gateway, int component) {
    }

    /** What a component of a gateway is: the motion or the illuminance of one BLU Motion. */
    public record Target(OccupancySensor sensor, int object) {

        public boolean motion() {
            return object == MOTION_OBJECT;
        }
    }

    //Never fails; one device after the other
    public Mono<Void> read() {
        return Mono.defer(() -> {
            final List<Gateway> gateways = occupancyRegistry.gateways();
            //a gateway the registry no longer lists is read no more: its components go with it
            final Set<String> listed = gateways.stream().map(Gateway::deviceId).collect(Collectors.toSet());
            components.keySet().removeIf(key -> !listed.contains(key.gateway()));

            final List<OccupancySensor> presences = occupancyRegistry.sensors().stream()
                .filter(sensor -> sensor.binding() instanceof SensorBinding.Presence)
                .toList();
            return Flux.fromIterable(gateways).concatMap(this::readGateway)
                .thenMany(Flux.fromIterable(presences).concatMap(this::readPresence))
                .then();
        });
    }

    /** What a component of a gateway is, asked before the body of its status topic is read. */
    public Optional<Target> targetOf(final String gatewayId, final Integer component) {
        return gatewayId == null || component == null
            ? Optional.empty()
            : Optional.ofNullable(components.get(new GatewayComponent(gatewayId.toLowerCase(Locale.ROOT), component)));
    }

    private Mono<Void> readGateway(final Gateway gateway) {
        return shellyRpcClient.dynamicComponents(gateway.address())
            .doOnNext(list -> {
                apply(gateway, list);
                answered("gateway " + gateway.address(), "Gateway " + gateway.id() + " (" + gateway.name() + ")");
            })
            .doOnError(e -> failed("gateway " + gateway.address(), "Gateway " + gateway.id() + " (" + gateway.name() + ")", e))
            .onErrorComplete()
            .then();
    }

    //the motion and illuminance components of the gateway that belong to a BLU Motion of the registry,
    //each with what it holds now; a component of another device, or of another object, is not this service's
    void apply(final Gateway gateway, final List<ShellyComponent> list) {
        final Map<String, OccupancySensor> byAddress = occupancyRegistry.sensors().stream()
            .filter(sensor -> sensor.binding() instanceof SensorBinding.BluMotion)
            .collect(Collectors.toMap(sensor -> ((SensorBinding.BluMotion) sensor.binding()).address(),
                Function.identity(), (a, b) -> a));
        final Map<GatewayComponent, Target> found = new HashMap<>();

        list.stream()
            .filter(component -> component.getKey() != null && component.getKey().startsWith(SENSOR))
            .forEach(component -> as(component.getConfig(), BthomeSensorConfig.class)
                .filter(config -> config.getId() != null && config.getAddr() != null && config.getObjId() != null
                    && (config.getObjId() == MOTION_OBJECT || config.getObjId() == ILLUMINANCE_OBJECT))
                .flatMap(config -> Optional.ofNullable(byAddress.get(config.getAddr().toLowerCase(Locale.ROOT)))
                    .map(sensor -> new Target(sensor, config.getObjId()))
                    .map(target -> {
                        found.put(new GatewayComponent(gateway.deviceId(), config.getId()), target);
                        return target;
                    }))
                .ifPresent(target -> as(component.getStatus(), BthomeSensorStatus.class)
                    .ifPresentOrElse(status -> observe(target, status), () -> occupancyTracker.confirm(target.sensor()))));

        components.keySet().removeIf(key -> key.gateway().equals(gateway.deviceId()) && !found.containsKey(key));
        components.putAll(found);
    }

    /** A status of a BLU component, published or read: a truth is motion, a number is lux, anything else confirms. */
    public void observe(final Target target, final BthomeSensorStatus status) {
        final Instant heardAt = status.getLastUpdatedTs() == null ? null : Instant.ofEpochSecond(status.getLastUpdatedTs());
        if (target.motion()) {
            occupancyTracker.bluMotion(target.sensor(), status.getValue() instanceof Boolean motion ? motion : null, heardAt);
        } else {
            occupancyTracker.bluLux(target.sensor(), status.getValue() instanceof Number lux ? lux.doubleValue() : null, heardAt);
        }
    }

    private Mono<Void> readPresence(final OccupancySensor sensor) {
        final SensorBinding.Presence presence = (SensorBinding.Presence) sensor.binding();
        final String device = "Presence sensor " + sensor.id() + " (" + sensor.name() + ")";
        return shellyRpcClient.dynamicComponents(presence.address())
            .doOnNext(list -> list.stream()
                .filter(component -> (ZONE + presence.zone()).equals(component.getKey()))
                .findFirst()
                .flatMap(component -> as(component.getStatus(), PresenceStatus.class))
                .map(PresenceStatus::getValue)
                .ifPresent(occupied -> occupancyTracker.presence(sensor, occupied, false)))
            .then(shellyRpcClient.status(presence.address()))
            .doOnNext(status -> illuminance(status.get(ILLUMINANCE))
                .ifPresent(read -> occupancyTracker.illumination(sensor, read.getIllumination(), read.getLux())))
            .doOnNext(status -> answered("presence " + sensor.id(), device))
            .doOnError(e -> failed("presence " + sensor.id(), device, e))
            .onErrorComplete()
            .then();
    }

    @SuppressWarnings("unchecked")
    private Optional<IlluminanceStatus> illuminance(final Object raw) {
        return raw instanceof Map<?, ?> map ? as((Map<String, Object>) map, IlluminanceStatus.class) : Optional.empty();
    }

    private void answered(final String read, final String device) {
        if (failing.remove(read)) {
            log.info("{} is read again", device);
        }
    }

    private void failed(final String read, final String device, final Throwable e) {
        if (failing.add(read)) {
            log.warn("{} could not be read: {}", device, e.toString());
        }
    }

    private <T> Optional<T> as(final Map<String, Object> raw, final Class<T> model) {
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jsonMapper.convertValue(raw, model));
        } catch (JacksonException | IllegalArgumentException notTheModel) {
            return Optional.empty();
        }
    }
}
