package cloud.cholewa.light.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Whether somebody is in a room, as its sensors say (HAS-260). What is not known is left out, never
 * false: {@code occupied} without a presence sensor, or while one of them is not known and none says
 * somebody is there; {@code since} while the occupancy is not known; {@code lastMotionAt} before the
 * first motion; {@code illumination} (the word of a Presence Gen4: dark, twilight, bright) and
 * {@code lux} (a number, from a BLU Motion or a Shelly Motion) until a sensor of the room reported one.
 * {@code motion} (HAS-349): a BLU Motion of the room reports a motion still going on - true while one
 * does, false once every one that is known reported its end; left out while none is known.
 * {@code motionClearedAt}: when the newest such motion ended (a motion held past
 * {@code light.occupancy.motion-held-at-most} counts as ended then).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoomOccupancy(
    String room,
    Boolean occupied,
    LocalDateTime since,
    LocalDateTime lastMotionAt,
    Boolean motion,
    LocalDateTime motionClearedAt,
    String illumination,
    Double lux,
    List<SensorView> sensors
) {

    /** A room no BLU Motion says anything of: whether a motion is going on is not known. */
    public RoomOccupancy(final String room, final Boolean occupied, final LocalDateTime since,
                         final LocalDateTime lastMotionAt, final String illumination, final Double lux,
                         final List<SensorView> sensors) {
        this(room, occupied, since, lastMotionAt, null, null, illumination, lux, sensors);
    }

    /** One sensor of the room: {@code reachable} left out where nothing can tell (a sleeping sensor). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SensorView(long id, String name, String kind, LocalDateTime lastReportAt, Boolean reachable) {
    }
}
