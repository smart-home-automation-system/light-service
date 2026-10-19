package cloud.cholewa.light.occupancy;

import cloud.cholewa.home.model.BthomeBinding;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.EatonBinding;
import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.registry.DeviceRegistryClient;
import cloud.cholewa.light.registry.RegistryCallException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//what the service can hear of a presence or motion sensor of the registry - the rest is skipped, never guessed
class OccupancyRegistryTest {

    private final DeviceRegistryClient client = mock(DeviceRegistryClient.class);
    private final OccupancyRegistry registry = new OccupancyRegistry(client);

    @Test
    void should_read_a_presence_gen4_by_its_zone() {
        final SmartDevice device = device(91, DeviceKind.PRESENCE_SENSOR).vendor(SmartVendor.SHELLY)
            .shelly(ShellyBinding.builder().address("10.0.0.1").generation(4).deviceId("ShellyPresence-CCBA97EE868C").channel(200).build())
            .build();

        assertThat(OccupancyRegistry.toSensor(device)).contains(new OccupancySensor(91, "A sensor", RoomName.OFFICE,
            SensorKind.PRESENCE, new SensorBinding.Presence("10.0.0.1", "shellypresence-ccba97ee868c", 200)));
    }

    //a presence sensor without its zone, or without the id it reports under, could never be heard
    @Test
    void should_skip_a_presence_sensor_the_service_cannot_hear() {
        assertThat(OccupancyRegistry.toSensor(device(91, DeviceKind.PRESENCE_SENSOR).vendor(SmartVendor.SHELLY)
            .shelly(ShellyBinding.builder().address("10.0.0.1").generation(4).deviceId("shellypresence-a").channel(0).build())
            .build())).isEmpty();
        assertThat(OccupancyRegistry.toSensor(device(91, DeviceKind.PRESENCE_SENSOR).vendor(SmartVendor.SHELLY)
            .shelly(ShellyBinding.builder().address("10.0.0.1").generation(4).channel(200).build())
            .build())).isEmpty();
        assertThat(OccupancyRegistry.toSensor(device(91, DeviceKind.PRESENCE_SENSOR).vendor(SmartVendor.EATON)
            .eaton(EatonBinding.builder().gateway(EatonGatewayType.LIGHTS).point(90).build())
            .build())).isEmpty();
    }

    @Test
    void should_read_the_three_kinds_of_motion_sensor() {
        assertThat(OccupancyRegistry.toSensor(device(96, DeviceKind.MOTION_SENSOR).vendor(SmartVendor.SHELLY)
            .bthome(BthomeBinding.builder().address("b0:c7:de:c5:f5:d5").build()).build()))
            .map(OccupancySensor::binding).contains(new SensorBinding.BluMotion("b0:c7:de:c5:f5:d5"));
        assertThat(OccupancyRegistry.toSensor(device(97, DeviceKind.MOTION_SENSOR).vendor(SmartVendor.SHELLY)
            .shelly(ShellyBinding.builder().address("10.0.0.9").generation(1).build()).build()))
            .map(OccupancySensor::binding).contains(new SensorBinding.Gen1Motion("10.0.0.9"));
        assertThat(OccupancyRegistry.toSensor(device(51, DeviceKind.MOTION_SENSOR).vendor(SmartVendor.EATON)
            .eaton(EatonBinding.builder().gateway(EatonGatewayType.LIGHTS).point(92).build()).build()))
            .map(OccupancySensor::binding).contains(new SensorBinding.EatonMotion(EatonGatewayType.LIGHTS, 92));
    }

    //a Gen2+ Shelly as a motion sensor has no channel of motion the service knows
    @Test
    void should_skip_a_motion_sensor_the_service_cannot_hear() {
        assertThat(OccupancyRegistry.toSensor(device(98, DeviceKind.MOTION_SENSOR).vendor(SmartVendor.SHELLY)
            .shelly(ShellyBinding.builder().address("10.0.0.9").generation(2).deviceId("shellyx-a").build()).build()))
            .isEmpty();
    }

    @Test
    void should_read_the_sensors_and_the_gateways_of_the_registry() {
        when(client.getPresenceSensors()).thenReturn(Mono.just(List.of(device(91, DeviceKind.PRESENCE_SENSOR)
            .vendor(SmartVendor.SHELLY)
            .shelly(ShellyBinding.builder().address("10.0.0.1").generation(4).deviceId("shellypresence-a").channel(200).build())
            .build())));
        when(client.getMotionSensors()).thenReturn(Mono.just(List.of(device(51, DeviceKind.MOTION_SENSOR)
            .vendor(SmartVendor.EATON).eaton(EatonBinding.builder().gateway(EatonGatewayType.LIGHTS).point(92).build()).build())));
        when(client.getBthomeGateways()).thenReturn(Mono.just(List.of(device(74, DeviceKind.BTHOME_GATEWAY)
            .vendor(SmartVendor.SHELLY)
            .shelly(ShellyBinding.builder().address("10.0.0.45").generation(3).deviceId("Shelly1PMG3-ABC").build()).build())));

        registry.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(registry.sensors()).extracting(OccupancySensor::id).containsExactly(91L, 51L);
        assertThat(registry.gateways()).containsExactly(
            new OccupancyRegistry.Gateway(74, "A sensor", "10.0.0.45", "shelly1pmg3-abc"));
    }

    //a database-service away for a moment must not make every room unknown
    @Test
    void should_keep_the_last_sensors_when_a_read_fails() {
        when(client.getPresenceSensors()).thenReturn(Mono.just(List.of(device(91, DeviceKind.PRESENCE_SENSOR)
            .vendor(SmartVendor.SHELLY)
            .shelly(ShellyBinding.builder().address("10.0.0.1").generation(4).deviceId("shellypresence-a").channel(200).build())
            .build())));
        when(client.getMotionSensors()).thenReturn(Mono.just(List.of()));
        when(client.getBthomeGateways()).thenReturn(Mono.just(List.of()));
        registry.refresh().as(StepVerifier::create).verifyComplete();

        when(client.getPresenceSensors()).thenReturn(Mono.error(new RegistryCallException("database-service answered 503")));
        registry.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(registry.sensors()).extracting(OccupancySensor::id).containsExactly(91L);
    }

    private static SmartDevice.SmartDeviceBuilder<?, ?> device(final long id, final DeviceKind kind) {
        return SmartDevice.builder().id(id).name("A sensor").room(RoomName.OFFICE).kind(kind).enabled(true);
    }
}
