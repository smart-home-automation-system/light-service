package cloud.cholewa.light.model.emptyhouse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** The phases of a night of the presence simulation (HAS-263), in the order they come. */
public enum PhaseName {
    EVENING("evening"), NIGHT("night"), MORNING("morning");

    private final String value;

    PhaseName(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    //exactly as written: an unknown phase is a body that cannot be read
    @JsonCreator
    public static PhaseName of(final String value) {
        return Arrays.stream(values()).filter(phase -> phase.value.equals(value)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("phase must be evening, night or morning"));
    }
}
