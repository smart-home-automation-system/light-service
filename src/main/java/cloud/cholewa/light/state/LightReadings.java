package cloud.cholewa.light.state;

import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.light.model.LightReading;
import cloud.cholewa.shelly.model.Gen1LightStatus;
import cloud.cholewa.shelly.model.Gen2LightStatus;
import cloud.cholewa.shelly.model.Relay;
import cloud.cholewa.shelly.model.RgbwStatus;
import cloud.cholewa.shelly.model.SwitchStatus;

import java.util.List;
import java.util.Optional;

//What each kind of device report says, as a reading. A report without on or off is no reading at
//all; a brightness only within 0..100, a channel of a color only within 0..255 - a value out of range
//is left out, never stored
public final class LightReadings {

    private LightReadings() {
    }

    //a Gen2+ relay - the MQTT status and the answer of Switch.GetStatus are the same model
    public static Optional<LightReading> ofSwitch(final SwitchStatus status) {
        return status.getOutput() == null
            ? Optional.empty()
            : Optional.of(new LightReading(status.getOutput(), null, null, null, status.getSource()));
    }

    //a Gen2+ dimmer: the brightness is reported off too - the level it comes back at
    public static Optional<LightReading> ofLight(final Gen2LightStatus status) {
        return status.getOutput() == null
            ? Optional.empty()
            : Optional.of(new LightReading(status.getOutput(), percent(status.getBrightness()), null, null,
                status.getSource()));
    }

    //a Gen2+ rgb or rgbw component; the profile rgb has no white
    public static Optional<LightReading> ofRgbw(final RgbwStatus status) {
        return status.getOutput() == null
            ? Optional.empty()
            : Optional.of(new LightReading(status.getOutput(), percent(status.getBrightness()),
                rgb(status.getRgb()), channel(status.getWhite()), status.getSource()));
    }

    //a Gen1 relay of /status
    public static Optional<LightReading> ofRelay(final Relay relay) {
        return relay.getIson() == null
            ? Optional.empty()
            : Optional.of(new LightReading(relay.getIson(), null, null, null, relay.getSource()));
    }

    //A Gen1 light of /status, read by the kind of the light: a dimmer by its brightness, an rgbw
    //light in the mode color by its gain - that mode has no brightness - and its channels
    public static Optional<LightReading> ofGen1Light(final Gen1LightStatus light, final LightKind kind) {
        if (light.getIson() == null) {
            return Optional.empty();
        }
        if (kind == LightKind.RGBW) {
            final List<Integer> rgb = light.getRed() == null || light.getGreen() == null || light.getBlue() == null
                ? null
                : rgb(List.of(light.getRed(), light.getGreen(), light.getBlue()));
            return Optional.of(new LightReading(light.getIson(), percent(light.getGain()), rgb,
                channel(light.getWhite()), light.getSource()));
        }
        return Optional.of(new LightReading(light.getIson(), percent(light.getBrightness()), null, null,
            light.getSource()));
    }

    //The status of an Eaton actuator, its info short (amx-service, HAS-229): a switch 0 off, 1 on; a
    //dimmer its level in percent - 0 is off, and says nothing of the level it comes back at
    public static Optional<LightReading> ofEatonStatus(final Integer value, final LightKind kind) {
        if (value == null) {
            return Optional.empty();
        }
        if (kind == LightKind.DIMMER) {
            if (value == 0) {
                return Optional.of(LightReading.switched(false));
            }
            return value > 0 && value <= 100
                ? Optional.of(new LightReading(true, value, null, null, null))
                : Optional.empty();
        }
        return switch (value) {
            case 0 -> Optional.of(LightReading.switched(false));
            case 1 -> Optional.of(LightReading.switched(true));
            default -> Optional.empty();
        };
    }

    private static Integer percent(final Integer value) {
        return value != null && value >= 0 && value <= 100 ? value : null;
    }

    private static Integer channel(final Integer value) {
        return value != null && value >= 0 && value <= 255 ? value : null;
    }

    private static List<Integer> rgb(final List<Integer> rgb) {
        return rgb != null && rgb.size() == 3 && rgb.stream().allMatch(value -> channel(value) != null)
            ? List.copyOf(rgb)
            : null;
    }
}
