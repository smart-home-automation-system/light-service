package cloud.cholewa.light.model.rule;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The rule of a room as stored: what the administrator wrote ({@link RoomRuleBody}), the switch of the
 * resident - {@code enabledByResident} false stops both rules of the room - and when it last changed.
 * A delay or a lux that was not given is left out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoomRule(
    String room,
    boolean autoOffEnabled,
    Integer autoOffDelayMinutes,
    boolean motionOnEnabled,
    Integer onlyBelowLux,
    Integer motionOffAfterMinutes,
    List<MotionPeriod> periods,
    int manualHoldMinutes,
    boolean enabledByResident,
    LocalDateTime updatedAt
) {
}
