package cloud.cholewa.light.service;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.model.LightReading;
import cloud.cholewa.light.model.LightView;
import cloud.cholewa.light.model.RoomLights;
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

//The reads of the lights - the registry's lights with the state the devices reported - and the Gen1
//actions, which are reports of a device that arrive over HTTP
@Slf4j
@Service
@RequiredArgsConstructor
public class LightQueryService {

    private final LightRegistry lightRegistry;
    private final LightStateTracker lightStateTracker;

    //every light, by room in the order of the rooms of the SDK, each room's in the order of the registry
    public Mono<List<RoomLights>> all() {
        return lightRegistry.all()
            .flatMap(lights -> Flux.fromIterable(lights).concatMap(this::view).collectList())
            .map(views -> {
                final Map<String, List<LightView>> byRoom = views.stream()
                    .collect(Collectors.groupingBy(LightView::room, LinkedHashMap::new, Collectors.toList()));
                return Arrays.stream(RoomName.values())
                    .map(RoomName::getValue)
                    .filter(byRoom::containsKey)
                    .map(room -> RoomLights.of(room, byRoom.get(room)))
                    .toList();
            });
    }

    public Mono<RoomLights> inRoom(final RoomName room) {
        return lightRegistry.inRoom(room)
            .flatMap(lights -> Flux.fromIterable(lights).concatMap(this::view).collectList())
            .map(views -> RoomLights.of(room.getValue(), views));
    }

    public Mono<LightView> one(final long id) {
        return lightRegistry.find(id).flatMap(this::view);
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

    private Mono<LightView> view(final Light light) {
        return lightStateTracker.status(light.id()).map(status -> LightView.of(light, status));
    }
}
