package cloud.cholewa.light.rule;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.error.LightError;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.rule.MotionPeriod;
import cloud.cholewa.light.model.rule.RoomRuleBody;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static cloud.cholewa.light.rule.RoomRuleFixtures.BEDROOM_PRESENCE;
import static cloud.cholewa.light.rule.RoomRuleFixtures.HALL_LEFT;
import static cloud.cholewa.light.rule.RoomRuleFixtures.KITCHEN_CEILING;
import static cloud.cholewa.light.rule.RoomRuleFixtures.LIGHTS;
import static cloud.cholewa.light.rule.RoomRuleFixtures.SENSORS;
import static cloud.cholewa.light.rule.RoomRuleFixtures.bedroomAutoOff;
import static cloud.cholewa.light.rule.RoomRuleFixtures.hallMotion;
import static cloud.cholewa.light.rule.RoomRuleFixtures.period;
import static cloud.cholewa.light.rule.RoomRuleFixtures.withPeriods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//every rule a body or its room can break, each with the words it is refused in
class RoomRuleRulesTest {

    @Test
    void should_accept_the_rules_of_the_readme() {
        assertThatCode(() -> {
            RoomRuleRules.check(bedroomAutoOff());
            RoomRuleRules.checkRoom(RoomName.BEDROOM, bedroomAutoOff(), LIGHTS, SENSORS);
            RoomRuleRules.check(hallMotion());
            RoomRuleRules.checkRoom(RoomName.HALL_UP, hallMotion(), LIGHTS, SENSORS);
        }).doesNotThrowAnyException();
    }

    //both rules off, nothing else given: stored, the delays kept for later may be left out
    @Test
    void should_accept_a_rule_with_both_parts_off() {
        assertThatCode(() -> RoomRuleRules.check(new RoomRuleBody(false, null, false, null, null, List.of(), 30)))
            .doesNotThrowAnyException();
    }

    @Test
    void should_refuse_a_missing_switch_of_a_rule() {
        refused(() -> RoomRuleRules.check(new RoomRuleBody(null, 15, false, null, null, List.of(), 60)),
            "autoOffEnabled is missing");
        refused(() -> RoomRuleRules.check(new RoomRuleBody(true, 15, null, null, null, List.of(), 60)),
            "motionOnEnabled is missing");
    }

    @Test
    void should_refuse_an_enabled_rule_without_its_delay() {
        refused(() -> RoomRuleRules.check(new RoomRuleBody(true, null, false, null, null, List.of(), 60)),
            "autoOffDelayMinutes is missing while autoOff is enabled");
        refused(() -> RoomRuleRules.check(withPeriods(new RoomRuleBody(false, null, true, null, null, null, 60),
                List.of(period("16:00", "22:00")))),
            "motionOffAfterMinutes is missing while motionOn is enabled");
        refused(() -> RoomRuleRules.check(new RoomRuleBody(true, 15, false, null, null, List.of(), null)),
            "manualHoldMinutes is missing");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 181, -1, Integer.MIN_VALUE})
    void should_refuse_a_delay_outside_1_to_180_minutes(final int minutes) {
        refused(() -> RoomRuleRules.check(new RoomRuleBody(true, minutes, false, null, null, List.of(), 60)),
            "autoOffDelayMinutes is between 1 and 180 minutes");
        //a rule that is off keeps a delay in range only
        refused(() -> RoomRuleRules.check(new RoomRuleBody(false, 15, false, null, minutes, List.of(), 60)),
            "motionOffAfterMinutes is between 1 and 180 minutes");
        refused(() -> RoomRuleRules.check(new RoomRuleBody(false, 15, false, null, null, List.of(), minutes)),
            "manualHoldMinutes is between 1 and 180 minutes");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 180})
    void should_accept_the_bounds_of_a_delay(final int minutes) {
        assertThatCode(() -> RoomRuleRules.check(new RoomRuleBody(true, minutes, false, null, minutes, List.of(), minutes)))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 100_001})
    void should_refuse_a_lux_out_of_range(final int lux) {
        refused(() -> RoomRuleRules.check(new RoomRuleBody(true, 15, false, lux, null, List.of(), 60)),
            "onlyBelowLux is between 1 and 100000 lx");
    }

    @Test
    void should_refuse_missing_periods() {
        refused(() -> RoomRuleRules.check(new RoomRuleBody(true, 15, false, null, null, null, 60)),
            "periods is missing - an empty list for none");
    }

    @Test
    void should_refuse_motion_on_without_a_period() {
        refused(() -> RoomRuleRules.check(withPeriods(hallMotion(), List.of())),
            "motionOn needs at least one period - without one no motion switches a light on");
    }

    @Test
    void should_refuse_more_than_twelve_periods() {
        final List<MotionPeriod> periods = new ArrayList<>();
        for (int hour = 0; hour < 13; hour++) {
            periods.add(new MotionPeriod(LocalTime.of(hour, 0), LocalTime.of(hour, 30), null, null, null));
        }
        refused(() -> RoomRuleRules.check(withPeriods(hallMotion(), periods)), "periods has at most 12 periods");
    }

    @Test
    void should_refuse_a_period_without_its_times() {
        refused(() -> RoomRuleRules.check(withPeriods(hallMotion(),
                List.of(new MotionPeriod(LocalTime.NOON, null, null, null, null)))),
            "a period has a from and a to");
        refused(() -> RoomRuleRules.check(withPeriods(hallMotion(), Arrays.asList(period("06:00", "08:00"), null))),
            "a period is missing");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "0||               | brightness is between 1 and 100",
        "101||             | brightness is between 1 and 100",
        "|256,0,0|         | rgb is three values between 0 and 255",
        "|255,0|           | rgb is three values between 0 and 255",
        "|-1,0,0|          | rgb is three values between 0 and 255",
        "||256             | white is between 0 and 255",
        "||-1              | white is between 0 and 255"
    })
    void should_refuse_a_period_out_of_range(final Integer brightness, final String rgb, final Integer white,
                                             final String reason) {
        final List<Integer> values = rgb == null ? null : Arrays.stream(rgb.split(",")).map(Integer::valueOf).toList();
        refused(() -> RoomRuleRules.check(withPeriods(hallMotion(),
                List.of(new MotionPeriod(LocalTime.of(6, 0), LocalTime.of(8, 0), brightness, values, white)))),
            reason);
    }

    //the second period against the first; one that crosses midnight is two pieces, the same from and
    //to is the whole day
    @ParameterizedTest
    @CsvSource({
        "16:00,22:00,21:00,23:00",
        "16:00,22:00,17:00,18:00",
        "22:00,06:00,05:00,07:00",
        "22:00,06:00,23:00,01:00",
        "22:00,06:00,20:00,23:00",
        "00:00,00:00,12:00,13:00",
        "07:30,07:30,22:00,06:00",
        "16:00,22:00,16:00,22:00"
    })
    void should_refuse_overlapping_periods(final String firstFrom, final String firstTo, final String secondFrom,
                                           final String secondTo) {
        refused(() -> RoomRuleRules.check(withPeriods(hallMotion(),
                List.of(period(firstFrom, firstTo), period(secondFrom, secondTo)))),
            "periods do not overlap: " + firstFrom + "-" + firstTo + " and " + secondFrom + "-" + secondTo);
    }

    //one ends where the next starts, gaps are no light
    @ParameterizedTest
    @CsvSource({
        "16:00,22:00,22:00,06:00",
        "22:00,06:00,06:00,08:00",
        "22:00,06:00,07:00,21:00",
        "00:00,00:00,,"
    })
    void should_accept_periods_that_touch_or_leave_gaps(final String firstFrom, final String firstTo,
                                                        final String secondFrom, final String secondTo) {
        final List<MotionPeriod> periods = secondFrom == null
            ? List.of(period(firstFrom, firstTo))
            : List.of(period(firstFrom, firstTo), period(secondFrom, secondTo));
        assertThatCode(() -> RoomRuleRules.check(withPeriods(hallMotion(), periods))).doesNotThrowAnyException();
    }

    @Test
    void should_refuse_a_room_without_lights() {
        refused(() -> RoomRuleRules.checkRoom(RoomName.BEDROOM, bedroomAutoOff(), List.of(KITCHEN_CEILING), SENSORS),
            "the bedroom has no light to switch");
    }

    //the halls on Eaton PIRs stay in NetLinx - even a rule with both parts off
    @Test
    void should_refuse_a_room_with_eaton_pirs_only() {
        refused(() -> RoomRuleRules.checkRoom(RoomName.STAIRS, new RoomRuleBody(false, null, false, null, null, List.of(), 30),
                LIGHTS, SENSORS),
            "the stairs has Eaton PIR sensors only - its lights stay with NetLinx");
    }

    @Test
    void should_refuse_auto_off_in_a_room_without_a_sensor() {
        refused(() -> RoomRuleRules.checkRoom(RoomName.KITCHEN, bedroomAutoOff(), LIGHTS, SENSORS),
            "autoOff needs a presence or motion sensor in the kitchen");
    }

    @Test
    void should_accept_a_rule_with_both_parts_off_in_a_room_without_a_sensor() {
        assertThatCode(() -> RoomRuleRules.checkRoom(RoomName.KITCHEN,
            new RoomRuleBody(false, null, false, null, null, List.of(), 30), LIGHTS, SENSORS))
            .doesNotThrowAnyException();
    }

    //a presence sensor says nobody is there, never that somebody moved
    @Test
    void should_refuse_motion_on_in_a_room_with_a_presence_sensor_only() {
        refused(() -> RoomRuleRules.checkRoom(RoomName.BEDROOM,
                withPeriods(hallMotion(), List.of(period("16:00", "22:00"))), LIGHTS, List.of(BEDROOM_PRESENCE)),
            "motionOn needs a motion sensor in the bedroom");
    }

    @Test
    void should_refuse_a_colour_in_a_room_without_an_rgbw_light() {
        refused(() -> RoomRuleRules.checkRoom(RoomName.BEDROOM,
                withPeriods(bedroomAutoOff(), List.of(new MotionPeriod(LocalTime.of(22, 0), LocalTime.of(6, 0), 10, null, 128))),
                LIGHTS, SENSORS),
            "a colour needs an rgbw light in the bedroom");
    }

    @Test
    void should_refuse_a_brightness_in_a_room_of_relays() {
        refused(() -> RoomRuleRules.checkRoom(RoomName.KITCHEN,
                new RoomRuleBody(false, null, false, null, null, List.of(new MotionPeriod(LocalTime.of(22, 0),
                    LocalTime.of(6, 0), 10, null, null)), 30),
                LIGHTS, SENSORS),
            "a brightness needs a light that dims in the kitchen");
    }

    @Test
    void should_accept_a_colour_where_one_light_of_the_room_has_one() {
        assertThatCode(() -> RoomRuleRules.checkRoom(RoomName.HALL_UP, hallMotion(), List.of(HALL_LEFT), SENSORS))
            .doesNotThrowAnyException();
    }

    private static void refused(final ThrowingCallable check, final String reason) {
        assertThatThrownBy(check)
            .isInstanceOfSatisfying(LightException.class, refusal -> {
                assertThat(refusal.getError()).isEqualTo(LightError.INVALID_RULE);
                assertThat(refusal.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            })
            .hasMessage(reason);
    }
}
