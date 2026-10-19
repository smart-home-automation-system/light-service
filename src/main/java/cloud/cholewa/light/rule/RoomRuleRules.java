package cloud.cholewa.light.rule;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.rule.MotionPeriod;
import cloud.cholewa.light.model.rule.RoomRuleBody;
import cloud.cholewa.light.occupancy.OccupancySensor;
import cloud.cholewa.light.occupancy.SensorBinding;
import cloud.cholewa.light.occupancy.SensorKind;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

//What the rule of a room has to say, and what its room has to have, before it is stored. Nothing in a
//body has a default - a field left out is refused, not read as off. The schema (V2) repeats what one
//row can say and would answer with the name of a constraint, where this answers in words by the names
//of the API. The first broken rule is the answer, INVALID_RULE with it as details.
//check() reads the body alone; checkRoom() what the registry knows of the room - its lights and sensors
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RoomRuleRules {

    static final int MIN_DELAY_MINUTES = 1;
    static final int MAX_DELAY_MINUTES = 180;
    static final int MIN_LUX = 1;
    static final int MAX_LUX = 100_000;
    static final int MAX_PERIODS = 12;
    private static final int MINUTES_OF_A_DAY = 24 * 60;

    public static void check(final RoomRuleBody rule) {
        require(rule.autoOffEnabled() != null, "autoOffEnabled is missing");
        require(rule.motionOnEnabled() != null, "motionOnEnabled is missing");
        checkDelay("autoOffDelayMinutes", rule.autoOffDelayMinutes(), rule.autoOffEnabled(), "autoOff");
        checkDelay("motionOffAfterMinutes", rule.motionOffAfterMinutes(), rule.motionOnEnabled(), "motionOn");
        require(rule.manualHoldMinutes() != null, "manualHoldMinutes is missing");
        checkRange("manualHoldMinutes", rule.manualHoldMinutes());
        if (rule.onlyBelowLux() != null) {
            require(rule.onlyBelowLux() >= MIN_LUX && rule.onlyBelowLux() <= MAX_LUX,
                "onlyBelowLux is between " + MIN_LUX + " and " + MAX_LUX + " lx");
        }
        checkPeriods(rule.periods());
        require(!rule.motionOnEnabled() || !rule.periods().isEmpty(),
            "motionOn needs at least one period - without one no motion switches a light on");
    }

    //The room as the registry knows it: lights to switch, and the sensors each enabled rule listens to.
    //An Eaton PIR does not count - the halls on Eaton PIRs stay in NetLinx (owner, HAS-262)
    public static void checkRoom(final RoomName room, final RoomRuleBody rule, final List<Light> lights,
                                 final List<OccupancySensor> sensors) {
        final String name = room.getValue();
        final List<Light> ofRoom = lights.stream().filter(light -> light.room() == room).toList();
        require(!ofRoom.isEmpty(), "the " + name + " has no light to switch");

        final List<OccupancySensor> heard = sensors.stream()
            .filter(sensor -> sensor.room() == room)
            .filter(sensor -> !(sensor.binding() instanceof SensorBinding.EatonMotion))
            .toList();
        require(!heard.isEmpty() || sensors.stream().noneMatch(sensor -> sensor.room() == room),
            "the " + name + " has Eaton PIR sensors only - its lights stay with NetLinx");
        require(!rule.autoOffEnabled() || !heard.isEmpty(),
            "autoOff needs a presence or motion sensor in the " + name);
        require(!rule.motionOnEnabled() || heard.stream().anyMatch(sensor -> sensor.kind() == SensorKind.MOTION),
            "motionOn needs a motion sensor in the " + name);

        if (rule.periods().stream().anyMatch(period -> period.brightness() != null)) {
            require(ofRoom.stream().anyMatch(Light::dims), "a brightness needs a light that dims in the " + name);
        }
        if (rule.periods().stream().anyMatch(MotionPeriod::colored)) {
            require(ofRoom.stream().anyMatch(Light::colors), "a colour needs an rgbw light in the " + name);
        }
    }

    //the delay of a rule that is on is given; of a rule that is off it may be kept for later
    private static void checkDelay(final String field, final Integer minutes, final boolean enabled, final String rule) {
        if (minutes == null) {
            require(!enabled, field + " is missing while " + rule + " is enabled");
            return;
        }
        checkRange(field, minutes);
    }

    private static void checkRange(final String field, final int minutes) {
        require(minutes >= MIN_DELAY_MINUTES && minutes <= MAX_DELAY_MINUTES,
            field + " is between " + MIN_DELAY_MINUTES + " and " + MAX_DELAY_MINUTES + " minutes");
    }

    private static void checkPeriods(final List<MotionPeriod> periods) {
        require(periods != null, "periods is missing - an empty list for none");
        require(periods.size() <= MAX_PERIODS, "periods has at most " + MAX_PERIODS + " periods");
        periods.forEach(RoomRuleRules::checkPeriod);

        for (int i = 0; i < periods.size(); i++) {
            for (int j = i + 1; j < periods.size(); j++) {
                require(!overlap(periods.get(i), periods.get(j)),
                    "periods do not overlap: " + describe(periods.get(i)) + " and " + describe(periods.get(j)));
            }
        }
    }

    private static void checkPeriod(final MotionPeriod period) {
        require(period != null, "a period is missing");
        require(period.from() != null && period.to() != null, "a period has a from and a to");
        if (period.brightness() != null) {
            require(period.brightness() >= 1 && period.brightness() <= 100, "brightness is between 1 and 100");
        }
        final List<Integer> rgb = period.rgb();
        if (rgb != null) {
            require(rgb.size() == 3 && rgb.stream().allMatch(value -> value != null && value >= 0 && value <= 255),
                "rgb is three values between 0 and 255");
        }
        if (period.white() != null) {
            require(period.white() >= 0 && period.white() <= 255, "white is between 0 and 255");
        }
    }

    //Each period as the minutes of a day it covers, from included and to not - one period may end
    //where the next starts. One that crosses midnight is two pieces, the same from and to the whole day
    private static boolean overlap(final MotionPeriod first, final MotionPeriod second) {
        for (final int[] one : pieces(first)) {
            for (final int[] other : pieces(second)) {
                if (one[0] < other[1] && other[0] < one[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<int[]> pieces(final MotionPeriod period) {
        final int from = minutes(period.from());
        final int to = minutes(period.to());
        final List<int[]> pieces = new ArrayList<>();
        if (from == to) {
            pieces.add(new int[]{0, MINUTES_OF_A_DAY});
        } else if (from < to) {
            pieces.add(new int[]{from, to});
        } else {
            pieces.add(new int[]{from, MINUTES_OF_A_DAY});
            pieces.add(new int[]{0, to});
        }
        return pieces;
    }

    private static int minutes(final LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static String describe(final MotionPeriod period) {
        return period.from() + "-" + period.to();
    }

    private static void require(final boolean rule, final String reason) {
        if (!rule) {
            throw LightException.invalidRule(reason);
        }
    }
}
