package cloud.cholewa.light.model;

import java.util.List;

/**
 * What one report of a device says about a light - an MQTT status, an answer to a poll, a Gen1
 * action, an Eaton status. A {@code null} is what the report did not say, never off or zero.
 *
 * @param on           never {@code null}: a report that does not say on or off is no reading
 * @param brightness   0 to 100, from a light that dims
 * @param rgb          red, green and blue, 0 to 255 each, from a light with colors
 * @param white        the white channel, 0 to 255
 * @param deviceSource what the device named as the cause of the change ({@code button}, {@code SHC})
 */
public record LightReading(boolean on, Integer brightness, List<Integer> rgb, Integer white, String deviceSource) {

    public static LightReading switched(final boolean on) {
        return new LightReading(on, null, null, null, null);
    }
}
