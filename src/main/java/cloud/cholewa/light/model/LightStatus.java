package cloud.cholewa.light.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The state of a light as the service knows it now, kept in memory. Every field may be
 * {@code null}, which is "not known": nothing before the first report, no brightness from a relay,
 * no reachability before anything was heard from the device or after a restart.
 *
 * @param changedAt when the state began - the switch, or the last change of brightness or color
 * @param source    who changed it: {@code dashboard}, {@code rule}, {@code empty-house} for a command
 *                  of this service, the word of a Shelly device, otherwise {@code external}
 */
public record LightStatus(
    Boolean on,
    Integer brightness,
    List<Integer> rgb,
    Integer white,
    LocalDateTime changedAt,
    String source,
    Boolean reachable
) {

    public static LightStatus unknown() {
        return new LightStatus(null, null, null, null, null, null, null);
    }

    public LightStatus withReachable(final Boolean reachable) {
        return new LightStatus(on, brightness, rgb, white, changedAt, source, reachable);
    }
}
