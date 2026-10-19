package cloud.cholewa.light.occupancy;

import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.config.OccupancyProperties;
import cloud.cholewa.light.model.RoomOccupancy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//the rules of a room: never empty before its sensors spoke, occupied when one says so, empty when all do
class OccupancyTrackerTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final Instant START = Instant.parse("2026-10-19T17:00:00Z");
    private static final LocalDateTime EVENING = LocalDateTime.ofInstant(START, ZONE);

    private static final OccupancySensor DESK = presence(91, RoomName.OFFICE, "shellypresence-ccba97ee868c");
    private static final OccupancySensor SOFA = presence(95, RoomName.OFFICE, "shellypresence-0a0b0c0d0e05");
    private static final OccupancySensor OFFICE_PIR = new OccupancySensor(96, "PIR", RoomName.OFFICE, SensorKind.MOTION,
        new SensorBinding.BluMotion("b0:c7:de:c5:f5:d5"));
    private static final OccupancySensor STAIRS = new OccupancySensor(51, "Stairs motion sensor", RoomName.STAIRS,
        SensorKind.MOTION, new SensorBinding.EatonMotion(EatonGatewayType.LIGHTS, 92));
    private static final OccupancySensor SANCTUM = new OccupancySensor(97, "Motion", RoomName.SANCTUM, SensorKind.MOTION,
        new SensorBinding.Gen1Motion("10.0.0.9"));

    private final OccupancyRegistry registry = mock(OccupancyRegistry.class);
    private final MovingClock clock = new MovingClock(START);
    private final OccupancyTracker tracker =
        new OccupancyTracker(registry, new OccupancyProperties(Duration.ofMinutes(30)), clock);

    @BeforeEach
    void setUp() {
        when(registry.sensors()).thenReturn(List.of(DESK, SOFA, OFFICE_PIR, STAIRS, SANCTUM));
    }

    //after a start nothing is known: no rule may act on a room that only looks empty
    @Test
    void should_know_nothing_of_a_room_whose_sensors_did_not_speak() {
        final RoomOccupancy office = tracker.room(RoomName.OFFICE);

        assertThat(office.occupied()).isNull();
        assertThat(office.since()).isNull();
        assertThat(office.lastMotionAt()).isNull();
        assertThat(office.sensors()).extracting(RoomOccupancy.SensorView::id).containsExactly(91L, 95L, 96L);
        assertThat(office.sensors()).allSatisfy(sensor -> {
            assertThat(sensor.lastReportAt()).isNull();
            assertThat(sensor.reachable()).isNull();
        });
    }

    @Test
    void should_answer_a_room_without_sensors_with_nothing_known() {
        final RoomOccupancy loft = tracker.room(RoomName.LOFT);

        assertThat(loft).isEqualTo(new RoomOccupancy("loft", null, null, null, null, null, List.of()));
    }

    @Test
    void should_take_a_room_as_occupied_when_one_presence_sensor_says_so() {
        tracker.presence(DESK, true, true);

        final RoomOccupancy office = tracker.room(RoomName.OFFICE);
        assertThat(office.occupied()).isTrue();
        assertThat(office.since()).isEqualTo(EVENING);
    }

    //the other sensor is not known yet - nobody here is not proven
    @Test
    void should_not_take_a_room_as_empty_while_one_presence_sensor_is_not_known() {
        tracker.presence(DESK, false, true);

        assertThat(tracker.room(RoomName.OFFICE).occupied()).isNull();
    }

    @Test
    void should_take_a_room_as_empty_when_every_presence_sensor_says_so_and_tell_since_when() {
        tracker.presence(DESK, true, true);
        tracker.presence(SOFA, false, true);
        clock.advance(Duration.ofMinutes(5));
        tracker.presence(DESK, false, true);
        clock.advance(Duration.ofMinutes(5));

        final RoomOccupancy office = tracker.room(RoomName.OFFICE);
        assertThat(office.occupied()).isFalse();
        assertThat(office.since()).isEqualTo(EVENING.plusMinutes(5));
    }

    //the last will of a device: what it said before is no longer known
    @Test
    void should_not_count_a_presence_sensor_that_left_the_broker() {
        tracker.presence(DESK, true, true);
        tracker.presence(SOFA, false, true);
        tracker.online(DESK, false);

        final RoomOccupancy office = tracker.room(RoomName.OFFICE);
        assertThat(office.occupied()).isNull();
        assertThat(office.since()).isNull();
        assertThat(office.sensors().getFirst().reachable()).isFalse();
    }

    //a read over HTTP is a reconciliation: MQTT alone says whether the device is on the broker
    @Test
    void should_not_take_a_read_presence_as_a_word_about_the_broker() {
        tracker.online(DESK, false);
        tracker.presence(DESK, true, false);

        assertThat(tracker.room(RoomName.OFFICE).sensors().getFirst().reachable()).isFalse();

        tracker.presence(DESK, true, true);
        assertThat(tracker.room(RoomName.OFFICE).sensors().getFirst().reachable()).isTrue();
    }

    @Test
    void should_keep_the_time_of_the_change_while_the_sensors_repeat_themselves() {
        tracker.presence(DESK, true, true);
        clock.advance(Duration.ofMinutes(3));
        tracker.presence(DESK, true, true);
        tracker.presence(SOFA, false, true);

        assertThat(tracker.room(RoomName.OFFICE).since()).isEqualTo(EVENING);
    }

    @Test
    void should_take_the_newest_motion_of_any_motion_sensor_of_the_room() {
        tracker.motion(STAIRS, true);
        clock.advance(Duration.ofMinutes(2));
        tracker.motion(STAIRS, false);

        final RoomOccupancy stairs = tracker.room(RoomName.STAIRS);
        assertThat(stairs.lastMotionAt()).isEqualTo(EVENING);
        //a motion sensor says nothing of presence, and a PIR of the AMX system nothing of its reach
        assertThat(stairs.occupied()).isNull();
        assertThat(stairs.sensors().getFirst().lastReportAt()).isEqualTo(EVENING.plusMinutes(2));
        assertThat(stairs.sensors().getFirst().reachable()).isNull();
    }

    //a BLU Motion is dated by its gateway
    @Test
    void should_date_the_motion_of_a_blu_sensor_by_its_gateway() {
        tracker.bluMotion(OFFICE_PIR, true, START.minusSeconds(40));

        final RoomOccupancy office = tracker.room(RoomName.OFFICE);
        assertThat(office.lastMotionAt()).isEqualTo(EVENING.minusSeconds(40));
        assertThat(office.sensors().get(2).reachable()).isTrue();
    }

    //a read of the gateway may bring an older state than a publish a moment before it
    @Test
    void should_keep_the_newer_report_of_a_blu_sensor() {
        tracker.bluMotion(OFFICE_PIR, true, START);
        tracker.bluMotion(OFFICE_PIR, true, START.minusSeconds(600));
        tracker.bluLux(OFFICE_PIR, 120.0, START);
        tracker.bluLux(OFFICE_PIR, 3.0, START.minusSeconds(600));

        final RoomOccupancy office = tracker.room(RoomName.OFFICE);
        assertThat(office.lastMotionAt()).isEqualTo(EVENING);
        assertThat(office.lux()).isEqualTo(120.0);
    }

    //a BLU sensor sends when it senses; the gateway that stops answering is what makes it unreachable
    @Test
    void should_take_a_blu_sensor_as_unreachable_once_its_gateway_was_silent_too_long() {
        tracker.bluMotion(OFFICE_PIR, false, START);
        clock.advance(Duration.ofMinutes(25));
        tracker.confirm(OFFICE_PIR);
        clock.advance(Duration.ofMinutes(30));

        assertThat(tracker.room(RoomName.OFFICE).sensors().get(2).reachable()).isTrue();

        clock.advance(Duration.ofMinutes(1));
        final RoomOccupancy.SensorView pir = tracker.room(RoomName.OFFICE).sensors().get(2);
        assertThat(pir.reachable()).isFalse();
        //a confirmation is not a report of the sensor
        assertThat(pir.lastReportAt()).isEqualTo(EVENING);
    }

    //the word of a Presence and the number of a motion sensor are two fields, each the newest of the room
    @Test
    void should_take_the_newest_illumination_and_lux_of_the_room() {
        tracker.illumination(DESK, "bright", null);
        clock.advance(Duration.ofMinutes(1));
        tracker.illumination(SOFA, "dark", null);
        tracker.bluLux(OFFICE_PIR, 4.0, null);

        final RoomOccupancy office = tracker.room(RoomName.OFFICE);
        assertThat(office.illumination()).isEqualTo("dark");
        assertThat(office.lux()).isEqualTo(4.0);
    }

    @Test
    void should_keep_the_lux_of_a_gen1_motion_and_say_nothing_of_its_reach() {
        tracker.motion(SANCTUM, true);
        tracker.illumination(SANCTUM, null, 25.0);

        final RoomOccupancy sanctum = tracker.room(RoomName.SANCTUM);
        assertThat(sanctum.lastMotionAt()).isEqualTo(EVENING);
        assertThat(sanctum.lux()).isEqualTo(25.0);
        assertThat(sanctum.illumination()).isNull();
        assertThat(sanctum.sensors().getFirst().reachable()).isNull();
    }

    private static OccupancySensor presence(final long id, final RoomName room, final String deviceId) {
        return new OccupancySensor(id, "Presence", room, SensorKind.PRESENCE,
            new SensorBinding.Presence("10.0.0.1", deviceId, 200));
    }

    private static final class MovingClock extends Clock {

        private Instant now;

        private MovingClock(final Instant now) {
            this.now = now;
        }

        void advance(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
