package cloud.cholewa.light.model;

import java.util.Arrays;
import java.util.Optional;

/** On or off, by the value of the {@code turn} parameter, exactly as written - a typo switches nothing. */
public enum Turn {
    ON("on"), OFF("off");

    private final String value;

    Turn(final String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean on() {
        return this == ON;
    }

    public static Optional<Turn> ofParameter(final String parameter) {
        return Arrays.stream(values()).filter(turn -> turn.value.equals(parameter)).findFirst();
    }
}
