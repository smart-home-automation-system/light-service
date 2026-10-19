package cloud.cholewa.light.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Who asked for a command, by the value of the {@code source} parameter: a person on the dashboard,
 * a rule of a room, or the empty house. Written to the log of every command; the state of a light
 * takes it with HAS-259.
 */
public enum CommandSource {
    DASHBOARD("dashboard"), RULE("rule"), EMPTY_HOUSE("empty-house");

    private final String value;

    CommandSource(final String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<CommandSource> ofParameter(final String parameter) {
        return Arrays.stream(values()).filter(source -> source.value.equals(parameter)).findFirst();
    }
}
