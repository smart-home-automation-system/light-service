package cloud.cholewa.light.model.rule;

import java.util.List;

/**
 * The rule of a room as the administrator writes it, the body of {@code PUT /rooms/{room}/rules}: whole,
 * nothing has a default - a field left out is refused, never read as off. The switch of the resident is
 * not part of it ({@code PUT /rooms/{room}/rules/resident-enabled}), a write keeps it.<br>
 * {@code autoOffEnabled}, {@code autoOffDelayMinutes} - every light of the room off that many minutes
 * after the room is empty (a presence sensor: after it says so; motion sensors only: after the last motion).<br>
 * {@code motionOnEnabled}, {@code onlyBelowLux}, {@code motionOffAfterMinutes}, {@code periods} - the lights
 * on at a motion while the room is darker than {@code onlyBelowLux} (left out: whatever the light), with
 * what the period of the moment gives (no period: no light), off again that many minutes after the last
 * motion.<br>
 * {@code manualHoldMinutes} - a light somebody else switched or changed is left alone that long.<br>
 * The delays are 1 to 180 minutes; the delay of a rule that is off may be left out or kept for later.
 */
public record RoomRuleBody(
    Boolean autoOffEnabled,
    Integer autoOffDelayMinutes,
    Boolean motionOnEnabled,
    Integer onlyBelowLux,
    Integer motionOffAfterMinutes,
    List<MotionPeriod> periods,
    Integer manualHoldMinutes
) {
}
