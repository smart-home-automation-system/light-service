package cloud.cholewa.light.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A light in the answer of a read: what the registry says of it and the state the devices reported.
 * A field the service does not know is left out - never answered as off or zero. {@code heldUntil}: the
 * rules of the room leave the light alone until then, after a change that was not theirs (HAS-262) - left
 * out when nothing holds it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LightView(
    long id,
    String name,
    String room,
    String vendor,
    String kind,
    Boolean on,
    Integer brightness,
    ColorView color,
    LocalDateTime changedAt,
    String source,
    Boolean reachable,
    LocalDateTime heldUntil
) {

    public static LightView of(final Light light, final LightStatus status) {
        return of(light, status, null);
    }

    public static LightView of(final Light light, final LightStatus status, final LocalDateTime heldUntil) {
        return new LightView(
            light.id(),
            light.name(),
            light.room().getValue(),
            light.vendor().getValue(),
            light.kind().getValue(),
            status.on(),
            light.dims() ? status.brightness() : null,
            light.colors() && (status.rgb() != null || status.white() != null)
                ? new ColorView(status.rgb(), status.white())
                : null,
            status.changedAt(),
            status.source(),
            status.reachable(),
            heldUntil
        );
    }

    /** The color of an rgbw light: what the device reported of it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ColorView(List<Integer> rgb, Integer white) {
    }
}
