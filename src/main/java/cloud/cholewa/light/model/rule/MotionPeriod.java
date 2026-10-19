package cloud.cholewa.light.model.rule;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalTime;
import java.util.List;

/**
 * One period of the motion scheme of a room: a motion between {@code from} and {@code to} switches the
 * lights on with what the period gives. Times are {@code HH:mm}, both ways; a {@code to} before
 * {@code from} is the next day (22:00 to 06:00), the same time is the whole day.<br>
 * {@code brightness} - 1 to 100, for the lights that dim; left out, a light comes on as it keeps itself.<br>
 * {@code rgb} - red, green and blue, 0 to 255 each, and {@code white} 0 to 255: for the rgbw lights of the
 * room, each left out where the lights keep their own.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MotionPeriod(
    @JsonFormat(pattern = MotionPeriod.MINUTES) LocalTime from,
    @JsonFormat(pattern = MotionPeriod.MINUTES) LocalTime to,
    Integer brightness,
    List<Integer> rgb,
    Integer white
) {

    static final String MINUTES = "HH:mm";

    public boolean colored() {
        return rgb != null || white != null;
    }
}
