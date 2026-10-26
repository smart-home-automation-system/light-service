package cloud.cholewa.light.rule;

import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightStatus;
import cloud.cholewa.light.model.RoomOccupancy;
import cloud.cholewa.light.model.rule.MotionPeriod;
import cloud.cholewa.light.model.rule.RoomRule;
import cloud.cholewa.light.rule.RuleEvaluator.LightSnapshot;
import cloud.cholewa.light.rule.RuleEvaluator.RoomSnapshot;
import cloud.cholewa.light.rule.RuleEvaluator.RuleAction;
import cloud.cholewa.light.rule.RuleEvaluator.Verdict;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static cloud.cholewa.light.rule.RoomRuleFixtures.BEDROOM_CEILING;
import static cloud.cholewa.light.rule.RoomRuleFixtures.HALL_LEFT;
import static org.assertj.core.api.Assertions.assertThat;

//The decisions of the engine without I/O (HAS-262): a room, its rule, its lights and the clock in, the
//commands out. The bedroom has a Presence Gen4 and switches itself off; the hall has a BLU Motion and is
//lit by motion - the rules of RoomRuleFixtures
class RuleEvaluatorTest {

    private static final LocalDateTime EVENING = LocalDateTime.of(2026, 10, 19, 20, 0);
    private static final Light BEDROOM_LAMP = new Light(32, "Lamp", BEDROOM_CEILING.room(), BEDROOM_CEILING.vendor(),
        BEDROOM_CEILING.binding(), BEDROOM_CEILING.kind());
    private static final Light HALL_RIGHT = new Light(121, "Right", HALL_LEFT.room(), HALL_LEFT.vendor(),
        HALL_LEFT.binding(), HALL_LEFT.kind());

    private static final RoomOccupancy.SensorView PRESENCE = new RoomOccupancy.SensorView(91, "Presence", "presence sensor",
        EVENING, true);
    private static final RoomOccupancy.SensorView MOTION = new RoomOccupancy.SensorView(95, "Motion", "motion sensor",
        EVENING, true);

    @Nested
    class AutoOff {

        //empty since 19:40, 15 minutes: at 19:55 the lights go off, a minute earlier they do not
        @Test
        void should_switch_off_every_light_that_is_on_after_the_presence_sensor_says_nobody_is_there() {
            final RoomSnapshot room = bedroom(empty(EVENING.minusMinutes(20), null),
                light(BEDROOM_CEILING, on("external", EVENING.minusHours(3))),
                light(BEDROOM_LAMP, off(EVENING.minusHours(3))));

            assertThat(RuleEvaluator.tick(room, EVENING.minusMinutes(6)).actions()).isEmpty();
            assertThat(RuleEvaluator.tick(room, EVENING.minusMinutes(5)).actions())
                .containsExactly(new RuleAction(BEDROOM_CEILING, false, RuleReason.AUTO_OFF, null));
        }

        @Test
        void should_keep_the_lights_while_somebody_is_there() {
            final RoomSnapshot room = bedroom(occupancy(true, EVENING.minusHours(2), null, null, PRESENCE),
                light(BEDROOM_CEILING, on("external", EVENING.minusHours(3))));

            assertThat(RuleEvaluator.tick(room, EVENING).actions()).isEmpty();
        }

        //a motion after the presence sensor said nobody is there counts from the motion
        @Test
        void should_count_the_delay_from_a_motion_after_the_presence_sensor_said_nobody() {
            final RoomSnapshot room = bedroom(empty(EVENING.minusMinutes(30), EVENING.minusMinutes(10)),
                light(BEDROOM_CEILING, on("external", EVENING.minusHours(3))));

            assertThat(RuleEvaluator.tick(room, EVENING).actions()).isEmpty();
            assertThat(RuleEvaluator.tick(room, EVENING.plusMinutes(5)).actions()).hasSize(1);
        }

        //motion sensors only: the room is empty after the last motion
        @Test
        void should_switch_off_after_the_last_motion_in_a_room_with_motion_sensors_only() {
            final RoomSnapshot room = hall(motionOnly(EVENING.minusMinutes(10), null),
                light(HALL_LEFT, on("external", EVENING.minusHours(1))));

            assertThat(RuleEvaluator.tick(room, EVENING.minusMinutes(1)).actions()).isEmpty();
            assertThat(RuleEvaluator.tick(room, EVENING).actions())
                .containsExactly(new RuleAction(HALL_LEFT, false, RuleReason.AUTO_OFF, null));
        }

        @Test
        void should_leave_a_light_that_is_off() {
            final RoomSnapshot room = bedroom(empty(EVENING.minusHours(1), null),
                light(BEDROOM_CEILING, off(EVENING.minusHours(3))));

            assertThat(RuleEvaluator.tick(room, EVENING).actions()).isEmpty();
        }

        //HAS-308 (owner, 2026-10-22): an off to a light that may be on is harmless - the kitchen bar the AMX could
        //not confirm stayed on all night
        @Test
        void should_switch_off_a_light_not_known_to_be_off() {
            final RoomSnapshot room = bedroom(empty(EVENING.minusHours(1), null),
                light(BEDROOM_CEILING, off(EVENING.minusHours(3))),
                light(BEDROOM_LAMP, LightStatus.unknown()));

            assertThat(RuleEvaluator.tick(room, EVENING).actions())
                .containsExactly(new RuleAction(BEDROOM_LAMP, false, RuleReason.AUTO_OFF, null));
        }

        //the hold stands on the change kept, known state or not: a person switched it 20 minutes ago, the hold is 60
        @Test
        void should_leave_a_light_not_known_but_held() {
            final RoomSnapshot room = bedroom(empty(EVENING.minusHours(1), null),
                light(BEDROOM_LAMP, new LightStatus(null, null, null, null, EVENING.minusMinutes(20), "external", null)));

            assertThat(RuleEvaluator.tick(room, EVENING).actions()).isEmpty();
            assertThat(RuleEvaluator.tick(room, EVENING.plusMinutes(40)).actions())
                .containsExactly(new RuleAction(BEDROOM_LAMP, false, RuleReason.AUTO_OFF, null));
        }

        //once: a light not known, sent its off, gets no other until it reports a change
        @Test
        void should_send_a_light_not_known_its_off_once() {
            final RoomSnapshot room = bedroom(empty(EVENING.minusHours(1), null),
                new LightSnapshot(BEDROOM_LAMP, LightStatus.unknown(), EVENING.minusMinutes(1)));

            assertThat(RuleEvaluator.tick(room, EVENING).actions()).isEmpty();
        }
    }

    @Nested
    class UnknownRooms {

        //after a start, or a Presence that left the broker
        @Test
        void should_skip_a_room_whose_presence_sensor_is_not_known() {
            final Verdict verdict = RuleEvaluator.tick(bedroom(occupancy(null, null, null, null, PRESENCE),
                light(BEDROOM_CEILING, on("external", EVENING.minusHours(3)))), EVENING);

            assertThat(verdict.actions()).isEmpty();
            assertThat(verdict.skipped()).isEqualTo("its presence sensor is not known");
        }

        @Test
        void should_skip_a_room_whose_motion_sensor_is_not_reachable() {
            final RoomOccupancy away = occupancy(null, null, EVENING.minusHours(1), null,
                new RoomOccupancy.SensorView(95, "Motion", "motion sensor", EVENING.minusHours(1), false));
            final Verdict verdict = RuleEvaluator.tick(hall(away, light(HALL_LEFT, on("external", EVENING.minusHours(2)))), EVENING);

            assertThat(verdict.actions()).isEmpty();
            assertThat(verdict.skipped()).isEqualTo("a motion sensor is not reachable");
        }

        @Test
        void should_skip_a_room_with_motion_sensors_only_before_its_first_motion() {
            final Verdict verdict = RuleEvaluator.tick(hall(motionOnly(null, null),
                light(HALL_LEFT, on("external", EVENING.minusHours(2)))), EVENING);

            assertThat(verdict.actions()).isEmpty();
            assertThat(verdict.skipped()).isEqualTo("no motion was seen since the start");
        }

        @Test
        void should_name_nothing_for_a_room_it_could_judge() {
            assertThat(RuleEvaluator.tick(bedroom(empty(EVENING, null)), EVENING).skipped()).isNull();
        }
    }

    @Nested
    class ManualHold {

        //switched on by hand 30 minutes ago, held for 60: auto-off leaves it until the hold ends
        @Test
        void should_leave_a_light_switched_by_somebody_else_until_the_hold_ends() {
            final RoomSnapshot room = bedroom(empty(EVENING.minusHours(1), null),
                light(BEDROOM_CEILING, on("dashboard", EVENING.minusMinutes(30))),
                light(BEDROOM_LAMP, on("shelly", EVENING.minusMinutes(59))));

            assertThat(RuleEvaluator.tick(room, EVENING).actions()).isEmpty();
            assertThat(RuleEvaluator.tick(room, EVENING.plusMinutes(1)).actions())
                .extracting(action -> action.light().id()).containsExactly(BEDROOM_LAMP.id());
            assertThat(RuleEvaluator.tick(room, EVENING.plusMinutes(30)).actions()).hasSize(2);
        }

        //a change of this service starts no hold
        @Test
        void should_hold_nothing_the_rules_or_the_empty_house_switched() {
            final RoomSnapshot room = bedroom(empty(EVENING.minusHours(1), null),
                light(BEDROOM_CEILING, on("rule", EVENING.minusMinutes(1))),
                light(BEDROOM_LAMP, on("empty-house", EVENING.minusMinutes(1))));

            assertThat(RuleEvaluator.tick(room, EVENING).actions()).hasSize(2);
        }

        @Test
        void should_keep_a_held_light_through_a_motion() {
            final RoomSnapshot room = hall(motionOnly(EVENING, 5.0),
                light(HALL_LEFT, off("external", EVENING.minusMinutes(10))),
                light(HALL_RIGHT, off(EVENING.minusHours(2))));

            assertThat(RuleEvaluator.motion(room, EVENING)).extracting(action -> action.light().id())
                .containsExactly(HALL_RIGHT.id());
        }

        @Test
        void should_answer_until_when_a_light_is_held() {
            final RoomRule rule = hallRule();

            assertThat(RuleEvaluator.heldUntil(on("external", EVENING), rule)).isEqualTo(EVENING.plusMinutes(30));
            assertThat(RuleEvaluator.heldUntil(on("rule", EVENING), rule)).isNull();
            assertThat(RuleEvaluator.heldUntil(LightStatus.unknown(), rule)).isNull();
            assertThat(RuleEvaluator.heldUntil(on("external", EVENING), null)).isNull();
        }
    }

    @Nested
    class MotionOn {

        //the hall rule: 16:00-22:00 at 80, 22:00-06:00 at 10 in warm orange
        @Test
        void should_light_the_room_with_the_scheme_of_the_period_of_the_moment() {
            final RoomSnapshot room = hall(motionOnly(EVENING, 5.0), light(HALL_LEFT, off(EVENING.minusHours(2))));
            final LocalDateTime night = EVENING.plusHours(3);
            final RoomSnapshot atNight = hall(motionOnly(night, 5.0), light(HALL_LEFT, off(EVENING.minusHours(2))));

            assertThat(RuleEvaluator.motion(room, EVENING)).singleElement()
                .satisfies(action -> {
                    assertThat(action.on()).isTrue();
                    assertThat(action.reason()).isEqualTo(RuleReason.MOTION_ON);
                    assertThat(action.period().brightness()).isEqualTo(80);
                });
            assertThat(RuleEvaluator.motion(atNight, night)).singleElement()
                .satisfies(action -> assertThat(action.period().rgb()).containsExactly(255, 120, 0));
        }

        //no period at 10:00: no light
        @Test
        void should_light_nothing_outside_the_periods() {
            final LocalDateTime morning = EVENING.withHour(10);
            final RoomSnapshot room = hall(motionOnly(morning, 5.0), light(HALL_LEFT, off(EVENING.minusDays(1))));

            assertThat(RuleEvaluator.motion(room, morning)).isEmpty();
        }

        //below 30 lx only; a lux not known never holds
        @Test
        void should_light_a_room_only_while_it_is_dark_enough() {
            final LightSnapshot left = light(HALL_LEFT, off(EVENING.minusHours(2)));

            assertThat(RuleEvaluator.motion(hall(motionOnly(EVENING, 29.9), left), EVENING)).hasSize(1);
            assertThat(RuleEvaluator.motion(hall(motionOnly(EVENING, 30.0), left), EVENING)).isEmpty();
            assertThat(RuleEvaluator.motion(hall(motionOnly(EVENING, null), left), EVENING)).hasSize(1);
        }

        //a read of a gateway after a start brings the last motion it heard - history, not a motion
        @Test
        void should_take_an_old_motion_for_history() {
            final RoomSnapshot room = hall(motionOnly(EVENING.minusMinutes(3), 5.0), light(HALL_LEFT, off(EVENING.minusHours(2))));

            assertThat(RuleEvaluator.motion(room, EVENING)).isEmpty();
        }

        @Test
        void should_leave_a_light_that_is_on() {
            final RoomSnapshot room = hall(motionOnly(EVENING, 5.0), light(HALL_LEFT, on("rule", EVENING.minusMinutes(1))));

            assertThat(RuleEvaluator.motion(room, EVENING)).isEmpty();
        }

        //sent at 20:00, not reported yet: not sent again; reported off at 20:01 after it: sent again
        @Test
        void should_send_a_light_one_command_until_it_reports_a_change() {
            final LightSnapshot waiting = new LightSnapshot(HALL_LEFT, off(EVENING.minusHours(2)), EVENING);
            final LightSnapshot changed = new LightSnapshot(HALL_LEFT, off(EVENING.plusMinutes(1)), EVENING);
            final LocalDateTime later = EVENING.plusMinutes(1);

            assertThat(RuleEvaluator.motion(hall(motionOnly(later, 5.0), waiting), later)).isEmpty();
            assertThat(RuleEvaluator.motion(hall(motionOnly(later, 5.0), changed), later)).hasSize(1);
        }

        @Test
        void should_do_nothing_with_a_rule_switched_off_by_the_resident_or_without_motion_on() {
            final LightSnapshot left = light(HALL_LEFT, off(EVENING.minusHours(2)));
            final RoomRule hall = hallRule();
            final RoomRule switchedOff = new RoomRule(hall.room(), hall.autoOffEnabled(), hall.autoOffDelayMinutes(),
                hall.motionOnEnabled(), hall.onlyBelowLux(), hall.motionOffAfterMinutes(), hall.periods(),
                hall.manualHoldMinutes(), false, hall.updatedAt());
            final RoomRule autoOffOnly = rule(bedroomRule(), true);

            assertThat(RuleEvaluator.motion(new RoomSnapshot(switchedOff, motionOnly(EVENING, 5.0), List.of(left)), EVENING)).isEmpty();
            assertThat(RuleEvaluator.motion(new RoomSnapshot(autoOffOnly, motionOnly(EVENING, 5.0), List.of(left)), EVENING)).isEmpty();
            assertThat(RuleEvaluator.tick(new RoomSnapshot(switchedOff, motionOnly(EVENING.minusHours(1), 5.0),
                List.of(light(HALL_LEFT, on("rule", EVENING.minusHours(1))))), EVENING)).isEqualTo(Verdict.NONE);
        }
    }

    //HAS-349: a BLU Motion that reports a motion still going on keeps its room occupied
    @Nested
    class MotionGoingOn {

        private static final LocalDateTime START = LocalDateTime.of(2026, 10, 25, 17, 28, 6);
        private static final LocalDateTime CLEARED = LocalDateTime.of(2026, 10, 25, 17, 59, 5);

        //the kitchen of 2026-10-25: auto-off switched the ceiling off at 17:38:59 while the sensor had reported
        //motion since 17:28:06 without a break - now it waits for the clear and counts its 10 minutes from it
        @Test
        void should_not_switch_off_while_a_motion_goes_on_and_count_the_delay_from_its_clear() {
            final LightSnapshot ceiling = light(HALL_LEFT, on("external", START.minusMinutes(30)));

            assertThat(RuleEvaluator.tick(hall(blu(START, true, null, MOTION), ceiling),
                LocalDateTime.of(2026, 10, 25, 17, 38, 59)).actions()).isEmpty();
            assertThat(RuleEvaluator.tick(hall(blu(START, true, null, MOTION), ceiling), CLEARED.minusSeconds(1))
                .actions()).isEmpty();

            final RoomSnapshot cleared = hall(blu(START, false, CLEARED, MOTION), ceiling);
            assertThat(RuleEvaluator.tick(cleared, CLEARED.plusMinutes(10).minusSeconds(1)).actions()).isEmpty();
            assertThat(RuleEvaluator.tick(cleared, CLEARED.plusMinutes(10)).actions())
                .containsExactly(new RuleAction(HALL_LEFT, false, RuleReason.AUTO_OFF, null));
        }

        //a presence sensor that says nobody is there does not empty a room where a motion goes on
        @Test
        void should_keep_a_room_with_a_presence_sensor_occupied_while_a_motion_goes_on() {
            final LightSnapshot ceiling = light(BEDROOM_CEILING, on("external", START.minusHours(1)));
            final RoomOccupancy nobody = new RoomOccupancy("bedroom", false, START.minusHours(1), START, true, null,
                null, 5.0, List.of(PRESENCE, MOTION));

            assertThat(RuleEvaluator.tick(bedroom(nobody, ceiling), START.plusHours(2)).actions()).isEmpty();
        }

        //motion-off waits for the clear too, and counts its 3 minutes from it
        @Test
        void should_not_switch_off_what_the_motion_lit_while_it_goes_on() {
            final LightSnapshot left = light(HALL_LEFT, on("rule", START));

            assertThat(RuleEvaluator.tick(hall(blu(START, true, null, MOTION), left), START.plusMinutes(20)).actions())
                .isEmpty();

            final RoomSnapshot cleared = hall(blu(START, false, CLEARED, MOTION), left);
            assertThat(RuleEvaluator.tick(cleared, CLEARED.plusMinutes(3).minusSeconds(1)).actions()).isEmpty();
            assertThat(RuleEvaluator.tick(cleared, CLEARED.plusMinutes(3)).actions())
                .containsExactly(new RuleAction(HALL_LEFT, false, RuleReason.MOTION_OFF, null));
        }

        //a motion that began after the clear of another counts from its own start
        @Test
        void should_count_from_the_later_of_the_last_motion_and_the_clear() {
            final LightSnapshot ceiling = light(HALL_LEFT, on("external", START.minusMinutes(30)));
            final LocalDateTime again = CLEARED.plusMinutes(4);

            final RoomSnapshot room = hall(blu(again, false, CLEARED, MOTION), ceiling);
            assertThat(RuleEvaluator.tick(room, again.plusMinutes(10).minusSeconds(1)).actions()).isEmpty();
            assertThat(RuleEvaluator.tick(room, again.plusMinutes(10)).actions()).hasSize(1);
        }
    }

    @Nested
    class MotionOff {

        //3 minutes after the last motion the lights the motion switched on go off - before auto-off's 10
        @Test
        void should_switch_off_what_the_motion_switched_on_after_the_last_motion() {
            final RoomSnapshot room = hall(motionOnly(EVENING.minusMinutes(3), 5.0),
                light(HALL_LEFT, on("rule", EVENING.minusMinutes(5))),
                light(HALL_RIGHT, on("external", EVENING.minusHours(2))));

            assertThat(RuleEvaluator.tick(room, EVENING.minusSeconds(1)).actions()).isEmpty();
            assertThat(RuleEvaluator.tick(room, EVENING).actions())
                .containsExactly(new RuleAction(HALL_LEFT, false, RuleReason.MOTION_OFF, null));
        }

        //a presence sensor that says somebody is there, or is not known, keeps them on
        @Test
        void should_keep_the_lights_while_a_presence_sensor_says_somebody_is_there_or_is_not_known() {
            final LightSnapshot left = light(HALL_LEFT, on("rule", EVENING.minusMinutes(20)));
            final RoomRule rule = rule(hallRule(), false);

            assertThat(RuleEvaluator.tick(new RoomSnapshot(rule,
                occupancy(true, EVENING.minusMinutes(20), EVENING.minusMinutes(10), 5.0, PRESENCE, MOTION), List.of(left)),
                EVENING).actions()).isEmpty();
            assertThat(RuleEvaluator.tick(new RoomSnapshot(rule,
                occupancy(null, null, EVENING.minusMinutes(10), 5.0, PRESENCE, MOTION), List.of(left)),
                EVENING).actions()).isEmpty();
            assertThat(RuleEvaluator.tick(new RoomSnapshot(rule,
                occupancy(false, EVENING.minusMinutes(20), EVENING.minusMinutes(10), 5.0, PRESENCE, MOTION), List.of(left)),
                EVENING).actions()).hasSize(1);
        }

        @Test
        void should_decide_nothing_before_the_first_motion() {
            final RoomSnapshot room = hall(motionOnly(null, null), light(HALL_LEFT, on("rule", EVENING.minusMinutes(20))));

            assertThat(RuleEvaluator.tick(room, EVENING).actions()).isEmpty();
        }
    }

    @Nested
    class Periods {

        @Test
        void should_find_the_period_of_a_time_from_included_to_not() {
            final List<MotionPeriod> periods = RoomRuleFixtures.hallMotion().periods();

            assertThat(RuleEvaluator.periodAt(periods, LocalTime.of(16, 0))).contains(periods.get(1));
            assertThat(RuleEvaluator.periodAt(periods, LocalTime.of(22, 0))).contains(periods.get(0));
            assertThat(RuleEvaluator.periodAt(periods, LocalTime.of(5, 59))).contains(periods.get(0));
            assertThat(RuleEvaluator.periodAt(periods, LocalTime.of(6, 0))).isEmpty();
            assertThat(RuleEvaluator.periodAt(List.of(RoomRuleFixtures.period("07:00", "07:00")), LocalTime.of(3, 0)))
                .isPresent();
        }
    }

    //the bedroom: auto-off after 15 minutes, a hold of 60
    private static RoomSnapshot bedroom(final RoomOccupancy occupancy, final LightSnapshot... lights) {
        return new RoomSnapshot(bedroomRule(), occupancy, List.of(lights));
    }

    //the hall: auto-off after 10 minutes, motion-on below 30 lx, off 3 minutes after the last motion, a hold of 30
    private static RoomSnapshot hall(final RoomOccupancy occupancy, final LightSnapshot... lights) {
        return new RoomSnapshot(hallRule(), occupancy, List.of(lights));
    }

    private static RoomRule bedroomRule() {
        final var body = RoomRuleFixtures.bedroomAutoOff();
        return new RoomRule("bedroom", body.autoOffEnabled(), body.autoOffDelayMinutes(), body.motionOnEnabled(),
            body.onlyBelowLux(), body.motionOffAfterMinutes(), body.periods(), body.manualHoldMinutes(), true, EVENING);
    }

    private static RoomRule hallRule() {
        final var body = RoomRuleFixtures.hallMotion();
        return new RoomRule("hall up", body.autoOffEnabled(), body.autoOffDelayMinutes(), body.motionOnEnabled(),
            body.onlyBelowLux(), body.motionOffAfterMinutes(), body.periods(), body.manualHoldMinutes(), true, EVENING);
    }

    //the rule with auto-off on or off
    private static RoomRule rule(final RoomRule rule, final boolean autoOff) {
        return new RoomRule(rule.room(), autoOff, rule.autoOffDelayMinutes(), rule.motionOnEnabled(), rule.onlyBelowLux(),
            rule.motionOffAfterMinutes(), rule.periods(), rule.manualHoldMinutes(), rule.enabledByResident(), rule.updatedAt());
    }

    private static RoomOccupancy empty(final LocalDateTime since, final LocalDateTime lastMotionAt) {
        return occupancy(false, since, lastMotionAt, null, PRESENCE);
    }

    //a room with a BLU Motion that says whether a motion is going on (HAS-349)
    private static RoomOccupancy blu(final LocalDateTime lastMotionAt, final Boolean motion, final LocalDateTime clearedAt,
                                     final RoomOccupancy.SensorView... sensors) {
        return new RoomOccupancy("room", null, null, lastMotionAt, motion, clearedAt, null, 5.0, List.of(sensors));
    }

    private static RoomOccupancy motionOnly(final LocalDateTime lastMotionAt, final Double lux) {
        return occupancy(null, null, lastMotionAt, lux, MOTION);
    }

    private static RoomOccupancy occupancy(final Boolean occupied, final LocalDateTime since, final LocalDateTime lastMotionAt,
                                           final Double lux, final RoomOccupancy.SensorView... sensors) {
        return new RoomOccupancy("room", occupied, since, lastMotionAt, null, lux, List.of(sensors));
    }

    private static LightSnapshot light(final Light light, final LightStatus status) {
        return new LightSnapshot(light, status, null);
    }

    private static LightStatus on(final String source, final LocalDateTime changedAt) {
        return new LightStatus(true, null, null, null, changedAt, source, true);
    }

    private static LightStatus off(final LocalDateTime changedAt) {
        return off("rule", changedAt);
    }

    private static LightStatus off(final String source, final LocalDateTime changedAt) {
        return new LightStatus(false, null, null, null, changedAt, source, true);
    }
}
