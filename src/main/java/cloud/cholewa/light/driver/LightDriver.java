package cloud.cholewa.light.driver;

import cloud.cholewa.light.model.Color;
import cloud.cholewa.light.model.Light;
import reactor.core.publisher.Mono;

/**
 * One kind of device. A command completes once the device, or amx-service for an Eaton light, took
 * it - never once the light is on: whether it is comes from what the device reports. A call that did
 * not end with that is an error of whatever kind; the service maps it. The service calls
 * {@link #brightness} only for a light that dims and {@link #color} only for one that has colors.
 */
public interface LightDriver {

    Mono<Void> turn(Light light, boolean on);

    /**
     * Switches the light on at that brightness.
     *
     * @param brightness 1 to 100
     */
    Mono<Void> brightness(Light light, int brightness);

    /** Switches the light on in that color; what the color leaves out the device keeps. */
    Mono<Void> color(Light light, Color color);
}
