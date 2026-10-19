package cloud.cholewa.light.registry;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Light;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The lights the service drives: the switched-on lights of the device registry, read at the start and
//every five minutes (LightRegistryRefresh). A failed refresh keeps the last list - a database-service
//that is away for a moment must not make every light unknown. Until the first refresh succeeds a
//lookup reads the registry itself, so a command right after a start is not refused because the pod
//came up before database-service
@Slf4j
@Component
@RequiredArgsConstructor
public class LightRegistry {

    private final DeviceRegistryClient deviceRegistryClient;

    //null until the first successful read; never null afterwards
    private final AtomicReference<List<Light>> lights = new AtomicReference<>();

    public Mono<Void> refresh() {
        return load()
            .doOnError(e -> log.warn("Refresh of the lights failed, the last list stays: {}", e.toString()))
            .onErrorComplete()
            .then();
    }

    //logged whenever the list changes, not only its size: a light moved to another room or address
    //must leave a trace of when the service learned it
    private Mono<List<Light>> load() {
        return Mono.defer(deviceRegistryClient::getLights)
            .map(LightRegistry::toLights)
            .doOnNext(read -> {
                final List<Light> previous = lights.getAndSet(read);
                if (!read.equals(previous)) {
                    log.info("Lights known from the device registry: {}", read.size());
                }
            });
    }

    public Mono<Light> find(final long id) {
        return known()
            .flatMap(list -> Mono.justOrEmpty(list.stream().filter(light -> light.id() == id).findFirst()))
            .switchIfEmpty(Mono.error(() -> LightException.notFound("no light with id " + id)));
    }

    public Mono<List<Light>> inRoom(final RoomName room) {
        return known()
            .map(list -> list.stream().filter(light -> light.room() == room).toList())
            .filter(list -> !list.isEmpty())
            .switchIfEmpty(Mono.error(() -> LightException.notFound("no light in the room " + room.getValue())));
    }

    //never read: an unknown light would be a lie, so the caller is told the registry is away
    private Mono<List<Light>> known() {
        return Mono.defer(() -> {
            final List<Light> list = lights.get();
            return list != null
                ? Mono.just(list)
                : load().onErrorMap(e -> !(e instanceof LightException), LightException::registryUnavailable);
        });
    }

    private static List<Light> toLights(final List<SmartDevice> devices) {
        return devices.stream()
            .filter(Objects::nonNull)
            .map(device -> {
                final Optional<Light> light = LightMapper.toLight(device);
                if (light.isEmpty()) {
                    log.warn("Device {} ({}) of the registry cannot be driven as a light and is skipped",
                        device.getId(), device.getName());
                }
                return light;
            })
            .flatMap(Optional::stream)
            .toList();
    }
}
