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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoomOccupancy(
    String room,
    Boolean occupied,
    LocalDateTime since,
    LocalDateTime lastMotionAt,
    String illumination,
    Double lux,
    List<SensorView> sensors
) {

    /** One sensor of the room: {@code reachable} left out where nothing can tell (a sleeping sensor). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SensorView(long id, String name, String kind, LocalDateTime lastReportAt, Boolean reachable) {
    }
}
