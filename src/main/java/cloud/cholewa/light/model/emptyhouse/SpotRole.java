package cloud.cholewa.light.model.emptyhouse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * What a spot of the plan does in its phase (HAS-263): {@code stay} - where somebody is (a living room by
 * evening, the bathroom by night); {@code path} - lit for a moment on the way between two stays or to a
 * visit (a hall, the stairs); {@code closing} - lit for a short while after the end of the phase, the
 * last light before bed.
 */
public enum SpotRole {
    STAY("stay"), PATH("path"), CLOSING("closing");

    private final String value;

    SpotRole(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static SpotRole of(final String value) {
        return Arrays.stream(values()).filter(role -> role.value.equals(value)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("role must be stay, path or closing"));
    }
}
