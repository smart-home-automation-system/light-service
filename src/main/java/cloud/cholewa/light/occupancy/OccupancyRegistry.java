package cloud.cholewa.light.occupancy;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.light.registry.DeviceRegistryClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

//The sensors of the occupancy (HAS-260) and the BTHome gateways that deliver the Bluetooth ones: the
//switched-on presence sensors, motion sensors and gateways of the device registry, read at the start and
//every five minutes (OccupancyRefresh). A failed read keeps the last lists - a database-service away for a
//moment must not make every room unknown. Never a read per event: the device queue carries 130 a minute
@Slf4j
@Component
@RequiredArgsConstructor
public class OccupancyRegistry {

    //the first zone of a Shelly: a channel from here on is a zone, below it an output (database-service 0.17.0)
    static final int FIRST_ZONE = 200;
    private static final int FIRST_GENERATION_ON_MQTT = 2;

    private final DeviceRegistryClient deviceRegistryClient;

    private final AtomicReference<List<OccupancySensor>> sensors = new AtomicReference<>(List.of());
    private final AtomicReference<List<Gateway>> gateways = new AtomicReference<>(List.of());
    //false until one read of the registry succeeded: until then an empty list is no answer
    private final AtomicBoolean loaded = new AtomicBoolean();

    /** A BTHome gateway of the registry: a Shelly on the network, by its MQTT id in lower case. */
    public record Gateway(long id, String name, String address, String deviceId) {
    }

    //Never fails: a registry away keeps what was read last
    public Mono<Void> refresh() {
        return Mono.defer(() -> Mono.zip(
                deviceRegistryClient.getPresenceSensors(),
                deviceRegistryClient.getMotionSensors(),
                deviceRegistryClient.getBthomeGateways()))
            .doOnNext(read -> {
                final List<OccupancySensor> readSensors = Stream.concat(read.getT1().stream(), read.getT2().stream())
                    .filter(Objects::nonNull)
                    .map(OccupancyRegistry::toSensor)
                    .flatMap(Optional::stream)
                    .toList();
                if (!readSensors.equals(sensors.getAndSet(readSensors))) {
                    log.info("Sensors of the occupancy known from the device registry: {}", readSensors.size());
                }
                gateways.set(toGateways(read.getT3()));
                loaded.set(true);
            })
            .doOnError(e -> log.warn("Refresh of the sensors of the occupancy failed, the last ones stay: {}", e.toString()))
            .onErrorComplete()
            .then();
    }

    public List<OccupancySensor> sensors() {
        return sensors.get();
    }

    /** Whether the registry was read once: a lookup before it would take every sensor for unknown. */
    public boolean loaded() {
        return loaded.get();
    }

    public List<Gateway> gateways() {
        return gateways.get();
    }

    //What the service can hear of a device of the registry, or nothing - with a WARN, since a sensor
    //registered and never heard would leave its room unknown without a word:
    //- a presence sensor: a Shelly of generation 2 or later with its MQTT id and a zone as its channel
    //- a motion sensor: a Bluetooth one (BLU Motion), a Shelly of the first generation (its actions), or a
    //  PIR of the AMX system
    static Optional<OccupancySensor> toSensor(final SmartDevice device) {
        if (device.getId() == null || device.getRoom() == null || device.getKind() == null) {
            return skipped(device, "it has no id, room or kind");
        }
        final SensorBinding binding = device.getKind() == DeviceKind.PRESENCE_SENSOR ? presence(device) : motion(device);
        if (binding == null) {
            return skipped(device, "the service cannot hear such a " + device.getKind().getValue());
        }
        return Optional.of(new OccupancySensor(device.getId(), device.getName(), device.getRoom(),
            device.getKind() == DeviceKind.PRESENCE_SENSOR ? SensorKind.PRESENCE : SensorKind.MOTION, binding));
    }

    private static SensorBinding presence(final SmartDevice device) {
        final var shelly = device.getShelly();
        return shelly != null && shelly.getAddress() != null && shelly.getDeviceId() != null
            && shelly.getGeneration() != null && shelly.getGeneration() >= FIRST_GENERATION_ON_MQTT
            && shelly.getChannel() != null && shelly.getChannel() >= FIRST_ZONE
            ? new SensorBinding.Presence(shelly.getAddress(), shelly.getDeviceId().toLowerCase(Locale.ROOT), shelly.getChannel())
            : null;
    }

    private static SensorBinding motion(final SmartDevice device) {
        if (device.getBthome() != null && device.getBthome().getAddress() != null) {
            return new SensorBinding.BluMotion(device.getBthome().getAddress().toLowerCase(Locale.ROOT));
        }
        if (device.getShelly() != null && device.getShelly().getAddress() != null
            && Integer.valueOf(1).equals(device.getShelly().getGeneration())) {
            return new SensorBinding.Gen1Motion(device.getShelly().getAddress());
        }
        if (device.getEaton() != null && device.getEaton().getGateway() != null && device.getEaton().getPoint() != null) {
            return new SensorBinding.EatonMotion(device.getEaton().getGateway(), device.getEaton().getPoint());
        }
        return null;
    }

    private static Optional<OccupancySensor> skipped(final SmartDevice device, final String why) {
        log.warn("Device {} ({}) of the registry is skipped as a sensor of the occupancy: {}", device.getId(), device.getName(), why);
        return Optional.empty();
    }

    //a gateway is a Shelly on the network with its MQTT id; one without either cannot be read
    private static List<Gateway> toGateways(final List<SmartDevice> devices) {
        return devices.stream()
            .filter(Objects::nonNull)
            .filter(device -> device.getId() != null && device.getShelly() != null
                && device.getShelly().getAddress() != null && device.getShelly().getDeviceId() != null)
            .map(device -> new Gateway(device.getId(), device.getName(), device.getShelly().getAddress(),
                device.getShelly().getDeviceId().toLowerCase(Locale.ROOT)))
            .toList();
    }
}
