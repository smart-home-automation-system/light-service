package cloud.cholewa.light.model;

import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;

/**
 * A light as the service knows it, from the device registry. Kept in memory only and never
 * serialized - an answer is mapped from it by hand.<br>
 * {@code kind} - what it can do: a relay switches, a dimmer also takes a brightness, an rgbw light
 * also takes a color.
 */
public record Light(
    long id,
    String name,
    RoomName room,
    SmartVendor vendor,
    LightBinding binding,
    LightKind kind
) {

    public boolean dims() {
        return kind == LightKind.DIMMER || kind == LightKind.RGBW;
    }

    public boolean colors() {
        return kind == LightKind.RGBW;
    }
}
