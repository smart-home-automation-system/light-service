package cloud.cholewa.light.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The lights of one room, in the order of the registry. {@code anyOn} is {@code true} when one of
 * them is known to be on, {@code false} when every one is known to be off, and left out otherwise -
 * a light nothing was heard of may be on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoomLights(String room, Boolean anyOn, List<LightView> lights) {

    public static RoomLights of(final String room, final List<LightView> lights) {
        final Boolean anyOn = lights.stream().anyMatch(light -> Boolean.TRUE.equals(light.on()))
            ? Boolean.TRUE
            : lights.stream().allMatch(light -> Boolean.FALSE.equals(light.on())) ? Boolean.FALSE : null;
        return new RoomLights(room, anyOn, lights);
    }
}
