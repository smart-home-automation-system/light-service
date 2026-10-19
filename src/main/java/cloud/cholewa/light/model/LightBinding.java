package cloud.cholewa.light.model;

/**
 * How a light is driven - one kind of device each, so a driver is chosen by the type alone; what the
 * light can do is its {@link Light#kind()}.
 */
public sealed interface LightBinding {

    /** A Shelly Gen1 device: {@code /relay}, {@code /light} or {@code /color} on its channel. */
    record ShellyGen1(String address, int channel) implements LightBinding {
    }

    /** A Shelly Gen2+ device: {@code Switch.*}, {@code Light.*}, {@code RGB.*} or {@code RGBW.*} with {@code id = channel}. */
    record ShellyGen2(String address, int channel) implements LightBinding {
    }

    /** An Eaton relay or dimmer on a data point - a group point included - switched through amx-service. */
    record Eaton(int point) implements LightBinding {
    }
}
