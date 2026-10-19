package cloud.cholewa.light.rule;

import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.model.rule.MotionPeriod;
import cloud.cholewa.light.model.rule.RoomRuleBody;
import cloud.cholewa.light.occupancy.OccupancySensor;
import cloud.cholewa.light.occupancy.SensorBinding;
import cloud.cholewa.light.occupancy.SensorKind;

import java.time.LocalTime;
import java.util.List;

//the two rules of the README: a bedroom that switches itself off, a hall lit by motion with a dim night
public final class RoomRuleFixtures {

    public static final Light BEDROOM_CEILING = new Light(31, "Ceiling", RoomName.BEDROOM, SmartVendor.EATON,
        new LightBinding.Eaton(31, EatonGatewayType.LIGHTS), LightKind.DIMMER);
    public static final Light HALL_LEFT = new Light(120, "Left", RoomName.HALL_UP, SmartVendor.SHELLY,
        new LightBinding.ShellyGen2("192.168.1.120", 0, "shellyplusrgbwpm-a1"), LightKind.RGBW);
    public static final Light KITCHEN_CEILING = new Light(21, "Ceiling", RoomName.KITCHEN, SmartVendor.EATON,
        new LightBinding.Eaton(27, EatonGatewayType.LIGHTS), LightKind.RELAY);
    public static final Light STAIRS_LIGHT = new Light(40, "Stairs", RoomName.STAIRS, SmartVendor.EATON,
        new LightBinding.Eaton(40, EatonGatewayType.LIGHTS), LightKind.RELAY);

    public static final OccupancySensor BEDROOM_PRESENCE = new OccupancySensor(91, "Presence", RoomName.BEDROOM,
        SensorKind.PRESENCE, new SensorBinding.Presence("192.168.1.91", "shellypresence-91", 200));
    public static final OccupancySensor HALL_MOTION = new OccupancySensor(95, "Motion", RoomName.HALL_UP,
        SensorKind.MOTION, new SensorBinding.BluMotion("aa:bb:cc:dd:ee:95"));
    public static final OccupancySensor STAIRS_PIR = new OccupancySensor(51, "PIR", RoomName.STAIRS,
        SensorKind.MOTION, new SensorBinding.EatonMotion(EatonGatewayType.BLINDS, 51));

    public static final List<Light> LIGHTS = List.of(BEDROOM_CEILING, HALL_LEFT, KITCHEN_CEILING, STAIRS_LIGHT);
    public static final List<OccupancySensor> SENSORS = List.of(BEDROOM_PRESENCE, HALL_MOTION, STAIRS_PIR);

    private RoomRuleFixtures() {
    }

    //every light off 15 minutes after the bedroom is empty, a light switched by hand left for an hour
    public static RoomRuleBody bedroomAutoOff() {
        return new RoomRuleBody(true, 15, false, null, null, List.of(), 60);
    }

    //on at a motion below 30 lx: bright in the evening, a dim warm light at night, nothing by day;
    //off 3 minutes after the last motion
    public static RoomRuleBody hallMotion() {
        return new RoomRuleBody(true, 10, true, 30, 3, List.of(
            new MotionPeriod(LocalTime.of(22, 0), LocalTime.of(6, 0), 10, List.of(255, 120, 0), null),
            new MotionPeriod(LocalTime.of(16, 0), LocalTime.of(22, 0), 80, null, null)
        ), 30);
    }

    public static RoomRuleBody withPeriods(final RoomRuleBody rule, final List<MotionPeriod> periods) {
        return new RoomRuleBody(rule.autoOffEnabled(), rule.autoOffDelayMinutes(), rule.motionOnEnabled(),
            rule.onlyBelowLux(), rule.motionOffAfterMinutes(), periods, rule.manualHoldMinutes());
    }

    public static MotionPeriod period(final String from, final String to) {
        return new MotionPeriod(LocalTime.parse(from), LocalTime.parse(to), null, null, null);
    }
}
