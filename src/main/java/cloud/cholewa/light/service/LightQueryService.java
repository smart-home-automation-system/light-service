package cloud.cholewa.light.service;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.model.LightReading;
import cloud.cholewa.light.model.LightView;
import cloud.cholewa.light.model.RoomLights;
import cloud.cholewa.light.model.RoomOccupancy;
import cloud.cholewa.light.occupancy.OccupancyRegistry;
import cloud.cholewa.light.occupancy.OccupancySensor;
import cloud.cholewa.light.occupancy.OccupancyTracker;
import cloud.cholewa.light.occupancy.SensorBinding;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.light.state.Gen1Action;
import cloud.cholewa.light.state.LightStateTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

//The reads of the lights - the registry's lights with the state the devices reported, and the occupancy
//of their rooms (HAS-260) - and the Gen1 actions, which are reports of a device that arrive over HTTP
@Slf4j
@Service
@RequiredArgsConstructor
public class LightQueryService {

    private final LightRegistry lightRegistry;
    private final LightStateTracker lightStateTracker;
    private final OccupancyRegistry occupancyRegistry;
    private final OccupancyTracker occupancyTracker;

    //every light, by room in the order of the rooms of the SDK, each room's in the order of the registry
    public Mono<List<RoomLights>> all() {
        return lightRegistry.all()
            .flatMap(lights -> Flux.fromIterable(lights).concatMap(this::view).collectList())
            .map(views -> {
                final Map<String, List<LightView>> byRoom = views.stream()
                    .collect(Collectors.groupingBy(LightView::room, LinkedHashMap::new, Collectors.toList()));
                return Arrays.stream(RoomName.values())
                    .filter(room -> byRoom.containsKey(room.getValue()))
                    .map(room -> RoomLights.of(room.getValue(), byRoom.get(room.getValue()), occupancyTracker.room(room)))
                    .toList();
            });
    }

    public Mono<RoomLights> inRoom(final RoomName room) {
        return lightRegistry.inRoom(room)
            .flatMap(lights -> Flux.fromIterable(lights).concatMap(this::view).collectList())
            .map(views -> RoomLights.of(room.getValue(), views, occupancyTracker.room(room)));
    }

    public Mono<LightView> one(final long id) {
        return lightRegistry.find(id).flatMap(this::view);
    }

    //a room without a sensor is a room nothing is known of - its sensors an empty list, never a 404:
    //the rules ask for every room with lights
    public Mono<RoomOccupancy> occupancy(final RoomName room) {
        return Mono.fromSupplier(() -> occupancyTracker.room(room));
    }

    //idempotent: the same action twice is the same state, confirmed. A device that is not a Gen1
    //light of the registry is a 404 - a device configured with the wrong id, or one removed
    public Mono<Void> gen1Action(final long device, final Gen1Action action) {
        return lightRegistry.find(device)
            .filter(light -> light.binding() instanceof LightBinding.ShellyGen1)
            .switchIfEmpty(Mono.error(() -> LightException.notFound("no Gen1 light with id " + device)))
            .flatMap(light -> {
                log.debug("Gen1 action {} of light {} ({})", action, light.id(), light.name());
                //the state is known once it is in memory; a store that failed is this service's
                //trouble, not the device's, and a device does not call again
                return lightStateTracker.observe(light, LightReading.switched(action.on()))
                    .onErrorResume(e -> {
                        log.error("The state of light {} ({}) could not be stored", light.id(), light.name(), e);
                        return Mono.empty();
                    });
            });
    }

    //The motion_on / motion_off actions of a Shelly Motion (HAS-260): it sleeps and cannot be asked, so
    //this is all it says - with the lux of the moment when its URL carries one. A device that is not a
    //Gen1 motion sensor of the registry, as last read, is a 404; until the registry was read once it is
    //read first, and a registry still away is REGISTRY_UNAVAILABLE - never a 404 for a real sensor
    public Mono<Void> gen1Motion(final long device, final boolean motion, final Double lux) {
        return Mono.defer(() -> occupancyRegistry.loaded() ? Mono.empty() : occupancyRegistry.refresh())
            .then(Mono.defer(() -> occupancyRegistry.loaded()
                ? Mono.empty()
                : Mono.error(LightException.registryUnavailable(new IllegalStateException("the registry was never read")))))
            .then(Mono.defer(() -> {
                final OccupancySensor sensor = occupancyRegistry.sensors().stream()
                    .filter(candidate -> candidate.id() == device && candidate.binding() instanceof SensorBinding.Gen1Motion)
                    .findFirst()
                    .orElse(null);
                if (sensor == null) {
                    return Mono.error(LightException.notFound("no Gen1 motion sensor with id " + device));
                }
                log.debug("Gen1 motion {} of sensor {} ({})", motion, sensor.id(), sensor.name());
                occupancyTracker.motion(sensor, motion);
                if (lux != null) {
                    occupancyTracker.illumination(sensor, null, lux);
                }
                return Mono.empty();
            }));
    }

    private Mono<LightView> view(final Light light) {
        return lightStateTracker.status(light.id()).map(status -> LightView.of(light, status));
    }
}
