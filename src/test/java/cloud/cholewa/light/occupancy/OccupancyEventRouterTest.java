package cloud.cholewa.light.occupancy;

import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import cloud.cholewa.shelly.model.BthomeSensorStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//the routing key decides first, by the sensors as last read; the body of another device is not read
class OccupancyEventRouterTest {

    private static final OccupancySensor DESK = new OccupancySensor(91, "Presence", RoomName.OFFICE, SensorKind.PRESENCE,
        new SensorBinding.Presence("10.0.0.1", "shellypresence-ccba97ee868c", 200));
    private static final OccupancySensor STAIRS = new OccupancySensor(51, "Stairs motion sensor", RoomName.STAIRS,
        SensorKind.MOTION, new SensorBinding.EatonMotion(EatonGatewayType.LIGHTS, 92));
    private static final OccupancySensor OFFICE_PIR = new OccupancySensor(96, "PIR", RoomName.OFFICE, SensorKind.MOTION,
        new SensorBinding.BluMotion("b0:c7:de:c5:f5:d5"));

    private final OccupancyRegistry registry = mock(OccupancyRegistry.class);
    private final OccupancyTracker tracker = mock(OccupancyTracker.class);
    private final OccupancyReads reads = mock(OccupancyReads.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final OccupancyEventRouter router =
        new OccupancyEventRouter(registry, tracker, reads, new ShellyMqttDecoder(jsonMapper), jsonMapper);

    @BeforeEach
    void setUp() {
        when(registry.sensors()).thenReturn(List.of(DESK, STAIRS, OFFICE_PIR));
        when(reads.targetOf(anyString(), any())).thenReturn(Optional.empty());
    }

    //the zone of the sensor - the id of the device in any case
    @Test
    void should_take_the_zone_of_a_presence_as_whether_somebody_is_there() {
        route("ShellyPresence-CCBA97EE868C.status.presencezone:200", "{\"id\":200,\"value\":true,\"num_objects\":1}");

        verify(tracker).presence(DESK, true, true);
    }

    //the Door zone of the office Presence is no zone of a sensor
    @Test
    void should_skip_another_zone_of_the_presence() {
        route("shellypresence-ccba97ee868c.status.presencezone:201", "{\"id\":201,\"value\":true,\"num_objects\":1}");

        verifyNoInteractions(tracker);
    }

    @Test
    void should_take_the_illuminance_of_a_presence_as_its_word() {
        route("shellypresence-ccba97ee868c.status.illuminance:0", "{\"id\":0,\"illumination\":\"twilight\"}");

        verify(tracker).illumination(DESK, "twilight", null);
    }

    @Test
    void should_take_the_last_will_of_a_presence() {
        route("shellypresence-ccba97ee868c.online", "false");

        verify(tracker).online(DESK, false);
    }

    @Test
    void should_take_the_switch_of_an_eaton_pir_as_motion_and_its_end() {
        route("eaton.lights.92", "{\"vendor\":\"eaton\",\"gateway\":\"lights\",\"point\":92,\"event\":\"switch on\"}");
        route("eaton.lights.92", "{\"vendor\":\"eaton\",\"gateway\":\"lights\",\"point\":92,\"event\":\"switch off\"}");

        verify(tracker).motion(STAIRS, true);
        verify(tracker).motion(STAIRS, false);
    }

    //a blind on the same point of the other gateway is not the sensor
    @Test
    void should_skip_an_eaton_event_of_the_other_gateway() {
        route("eaton.blinds.92", "{\"vendor\":\"eaton\",\"gateway\":\"blinds\",\"point\":92,\"event\":\"switch on\"}");

        verifyNoInteractions(tracker);
    }

    @Test
    void should_hand_a_mapped_blu_component_to_the_reads() {
        final OccupancyReads.Target motion = new OccupancyReads.Target(OFFICE_PIR, OccupancyReads.MOTION_OBJECT);
        when(reads.targetOf("shelly1pmg3-abc", 203)).thenReturn(Optional.of(motion));

        route("shelly1pmg3-abc.status.bthomesensor:203", "{\"id\":203,\"value\":true,\"last_updated_ts\":1792405079}");

        final ArgumentCaptor<BthomeSensorStatus> status = ArgumentCaptor.forClass(BthomeSensorStatus.class);
        verify(reads).observe(eq(motion), status.capture());
        assertThat(status.getValue().getValue()).isEqualTo(true);
        assertThat(status.getValue().getLastUpdatedTs()).isEqualTo(1792405079L);
    }

    //a window sensor of the same gateway is the blinds' - never read here
    @Test
    void should_skip_a_bthome_component_it_did_not_map() {
        route("shelly1pmg3-abc.status.bthomesensor:202", "not json at all");

        verify(reads).targetOf("shelly1pmg3-abc", 202);
        verify(reads, never()).observe(any(), any());
    }

    @Test
    void should_skip_a_device_that_is_no_sensor() {
        route("shellyplus1pm-a1b2c3.status.switch:0", "not json at all");
        route("eaton.lights.35", "not json at all");

        verifyNoInteractions(tracker);
    }

    @Test
    void should_fail_on_a_body_that_is_not_what_its_topic_carries() {
        router.route("shellypresence-ccba97ee868c.status.presencezone:200", "[1]".getBytes(StandardCharsets.UTF_8))
            .as(StepVerifier::create)
            .expectError()
            .verify();
        verifyNoInteractions(tracker);
    }

    private void route(final String routingKey, final String body) {
        router.route(routingKey, body.getBytes(StandardCharsets.UTF_8)).as(StepVerifier::create).verifyComplete();
    }
}
