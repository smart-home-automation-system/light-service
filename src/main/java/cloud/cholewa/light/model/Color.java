package cloud.cholewa.light.model;

import java.util.List;

/**
 * A color for an rgbw light, the body of {@code POST /{id}/color}. A part left out is not sent and
 * the device keeps it as it is; at least one part is given, the controller checks the ranges.<br>
 * {@code rgb} - red, green and blue, 0 to 255 each.<br>
 * {@code white} - the white channel, 0 to 255; a Gen2 device in the profile rgb has none.<br>
 * {@code brightness} - 1 to 100.
 */
public record Color(List<Integer> rgb, Integer white, Integer brightness) {
}
