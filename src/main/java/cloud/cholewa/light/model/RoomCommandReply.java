package cloud.cholewa.light.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The answer of a command for every light of a room: which took it and which did not, each one that
 * did not with the code of why. {@code switched} and {@code failed} are always there, empty or not.
 */
public record RoomCommandReply(String room, String turn, List<LightOutcome> switched, List<LightOutcome> failed) {

    /** One light of the room; {@code code} only for one that did not take the command. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LightOutcome(long id, String name, String code) {
    }
}
