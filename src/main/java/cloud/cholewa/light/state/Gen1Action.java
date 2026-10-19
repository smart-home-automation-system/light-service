package cloud.cholewa.light.state;

import java.util.Arrays;
import java.util.Optional;

/** The {@code event} of a Gen1 action call, exactly as written in the {@code out_on_url} / {@code out_off_url} of the device. */
public enum Gen1Action {
    ON("on"), OFF("off");

    private final String value;

    Gen1Action(final String value) {
        this.value = value;
    }

    public boolean on() {
        return this == ON;
    }

    public static Optional<Gen1Action> ofParameter(final String parameter) {
        return Arrays.stream(values()).filter(action -> action.value.equals(parameter)).findFirst();
    }
}
