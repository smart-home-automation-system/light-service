package cloud.cholewa.light.database;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** A colour as the tables keep it - {@code "r,g,b"} in light_state and light_motion_period - and back. */
public final class RgbColumn {

    private RgbColumn() {
    }

    public static String text(final List<Integer> rgb) {
        return rgb == null ? null : rgb.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public static List<Integer> values(final String rgb) {
        return rgb == null ? null : Arrays.stream(rgb.split(",")).map(Integer::valueOf).toList();
    }
}
