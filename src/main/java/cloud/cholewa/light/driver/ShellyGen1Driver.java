package cloud.cholewa.light.driver;

import cloud.cholewa.light.model.Color;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

//A Gen1 device by the kind of the light: a relay is /relay/<channel> (a Shelly 1, 1PM or a plug), a
//dimmer is /light/<channel> (a dimmer, a Duo, a bulb or an RGBW2 in the mode white), an rgbw light is
///color/<channel> (an RGBW2 or a bulb in the mode color - this mode has no brightness, the gain is
//its level). The answer of the device is not read here - the state comes from the device (HAS-259)
@Component
@RequiredArgsConstructor
public class ShellyGen1Driver implements LightDriver {

    private final ShellyGen1Client shellyGen1Client;

    @Override
    public Mono<Void> turn(final Light light, final boolean on) {
        final LightBinding.ShellyGen1 device = binding(light);
        return switch (light.kind()) {
            case RELAY -> shellyGen1Client.relay(device.address(), device.channel(), on).then();
            case DIMMER -> shellyGen1Client.light(device.address(), device.channel(), on, null).then();
            case RGBW -> shellyGen1Client.color(device.address(), device.channel(), on, null, null, null, null, null)
                .then();
        };
    }

    @Override
    public Mono<Void> brightness(final Light light, final int brightness) {
        final LightBinding.ShellyGen1 device = binding(light);
        return light.colors()
            ? shellyGen1Client.color(device.address(), device.channel(), true, null, null, null, null, brightness).then()
            : shellyGen1Client.light(device.address(), device.channel(), true, brightness).then();
    }

    @Override
    public Mono<Void> color(final Light light, final Color color) {
        final LightBinding.ShellyGen1 device = binding(light);
        final List<Integer> rgb = color.rgb();
        return shellyGen1Client.color(
            device.address(),
            device.channel(),
            true,
            rgb == null ? null : rgb.get(0),
            rgb == null ? null : rgb.get(1),
            rgb == null ? null : rgb.get(2),
            color.white(),
            color.brightness()
        ).then();
    }

    private static LightBinding.ShellyGen1 binding(final Light light) {
        return (LightBinding.ShellyGen1) light.binding();
    }
}
