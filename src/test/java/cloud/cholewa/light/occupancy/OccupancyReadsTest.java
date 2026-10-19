package cloud.cholewa.light.occupancy;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.occupancy.OccupancyRegistry.Gateway;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.ShellyComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the map of the gateways and the reconciliation of the Presence devices, every five minutes
class OccupancyReadsTest {

    private static final Gateway OFFICE_MAIN = new Gateway(74, "Office main", "10.0.0.45", "shelly1pmg3-abc");
    private static final OccupancySensor OFFICE_PIR = new OccupancySensor(96, "PIR", RoomName.OFFICE, SensorKind.MOTION,
        new SensorBinding.BluMotion("b0:c7:de:c5:f5:d5"));
    private static final OccupancySensor DESK = new OccupancySensor(91, "Presence", RoomName.OFFICE, SensorKind.PRESENCE,
        new SensorBinding.Presence("10.0.0.1", "shellypresence-ccba97ee868c", 200));

    private final OccupancyRegistry registry = mock(OccupancyRegistry.class);
    private final OccupancyTracker tracker = mock(OccupancyTracker.class);
    private final ShellyRpcClient rpc = mock(ShellyRpcClient.class);
    private final OccupancyReads reads = new OccupancyReads(registry, tracker, rpc, JsonMapper.builder().build());

    @BeforeEach
    void setUp() {
        when(registry.sensors()).thenReturn(List.of(OFFICE_PIR, DESK));
        when(registry.gateways()).thenReturn(List.of(OFFICE_MAIN));
        when(rpc.dynamicComponents("10.0.0.1")).thenReturn(Mono.just(List.of(
            component("presencezone:200", Map.of("id", 200, "value", true, "num_objects", 1), Map.of("id", 200, "name", "Room")),
            component("presencezone:201", Map.of("id", 201, "value", false, "num_objects", 0), Map.of("id", 201, "name", "Door")))));
        when(rpc.status("10.0.0.1")).thenReturn(Mono.just(Map.of("illuminance:0", Map.of("id", 0, "illumination", "dark"))));
    }

    //the motion and the illuminance of a BLU Motion of the registry, by its address in any case; the window
    //of a door sensor on the same gateway is not this service's
    @Test
    void should_map_the_components_of_a_blu_motion_and_take_what_they_hold() {
        when(rpc.dynamicComponents("10.0.0.45")).thenReturn(Mono.just(List.of(
            sensor(203, "B0:C7:DE:C5:F5:D5", 0x21, Map.of("id", 203, "value", true, "last_updated_ts", 1792405079)),
            sensor(204, "b0:c7:de:c5:f5:d5", 0x05, Map.of("id", 204, "value", 12, "last_updated_ts", 1792405079)),
            sensor(202, "60:ef:ab:43:ec:78", 0x2D, Map.of("id", 202, "value", false)))));

        reads.read().as(StepVerifier::create).verifyComplete();

        assertThat(reads.targetOf("Shelly1PMG3-ABC", 203)).contains(new OccupancyReads.Target(OFFICE_PIR, 0x21));
        assertThat(reads.targetOf("shelly1pmg3-abc", 204)).contains(new OccupancyReads.Target(OFFICE_PIR, 0x05));
        assertThat(reads.targetOf("shelly1pmg3-abc", 202)).isEmpty();
        verify(tracker).bluMotion(OFFICE_PIR, true, Instant.ofEpochSecond(1792405079));
        verify(tracker).bluLux(OFFICE_PIR, 12.0, Instant.ofEpochSecond(1792405079));
    }

    //a component without a status - the gateway stands for the sensor all the same
    @Test
    void should_confirm_a_sensor_whose_component_holds_nothing_yet() {
        when(rpc.dynamicComponents("10.0.0.45")).thenReturn(Mono.just(List.of(
            sensor(203, "b0:c7:de:c5:f5:d5", 0x21, Map.of()))));

        reads.read().as(StepVerifier::create).verifyComplete();

        verify(tracker).confirm(OFFICE_PIR);
    }

    //the main zone and the word of the light; MQTT alone says whether the device is on the broker
    @Test
    void should_reconcile_a_presence_with_its_zone_and_its_illuminance() {
        when(rpc.dynamicComponents("10.0.0.45")).thenReturn(Mono.just(List.of()));

        reads.read().as(StepVerifier::create).verifyComplete();

        verify(tracker).presence(DESK, true, false);
        verify(tracker).illumination(DESK, "dark", null);
    }

    //a gateway away keeps its map and lets its sensors age; the Presence is read all the same
    @Test
    void should_keep_the_map_of_a_gateway_that_does_not_answer() {
        when(rpc.dynamicComponents("10.0.0.45")).thenReturn(Mono.just(List.of(
            sensor(203, "b0:c7:de:c5:f5:d5", 0x21, Map.of("id", 203, "value", false)))));
        reads.read().as(StepVerifier::create).verifyComplete();

        when(rpc.dynamicComponents("10.0.0.45")).thenReturn(Mono.error(new IllegalStateException("no route to host")));
        reads.read().as(StepVerifier::create).verifyComplete();

        assertThat(reads.targetOf("shelly1pmg3-abc", 203)).isPresent();
        verify(tracker, times(2)).presence(DESK, true, false);
    }

    //a gateway the registry no longer lists is read no more, and its components go with it
    @Test
    void should_forget_the_components_of_a_gateway_the_registry_dropped() {
        when(rpc.dynamicComponents("10.0.0.45")).thenReturn(Mono.just(List.of(
            sensor(203, "b0:c7:de:c5:f5:d5", 0x21, Map.of("id", 203, "value", false)))));
        reads.read().as(StepVerifier::create).verifyComplete();

        when(registry.gateways()).thenReturn(List.of());
        reads.read().as(StepVerifier::create).verifyComplete();

        assertThat(reads.targetOf("shelly1pmg3-abc", 203)).isEmpty();
    }

    @Test
    void should_read_on_when_a_presence_does_not_answer() {
        when(rpc.dynamicComponents("10.0.0.45")).thenReturn(Mono.just(List.of()));
        when(rpc.dynamicComponents("10.0.0.1")).thenReturn(Mono.error(new IllegalStateException("timeout")));

        reads.read().as(StepVerifier::create).verifyComplete();

        verify(tracker, never()).presence(any(), anyBoolean(), anyBoolean());
    }

    private static ShellyComponent component(final String key, final Map<String, Object> status, final Map<String, Object> config) {
        return ShellyComponent.builder().key(key).status(new HashMap<>(status)).config(new HashMap<>(config)).build();
    }

    private static ShellyComponent sensor(final int id, final String address, final int object, final Map<String, Object> status) {
        return component("bthomesensor:" + id, status,
            Map.of("id", id, "addr", address, "obj_id", object, "idx", 0));
    }
}
