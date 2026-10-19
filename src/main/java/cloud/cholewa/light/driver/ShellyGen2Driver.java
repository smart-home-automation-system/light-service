package cloud.cholewa.light.driver;

import cloud.cholewa.light.driver.Gen2Profiles.ColorComponent;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Color;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.function.Function;

//A Gen2+ device by the kind of the light, with id = channel: a relay is Switch.Set, a dimmer
//Light.Set, an rgbw light RGB.Set or RGBW.Set by the profile of the device (Gen2Profiles). A command
//that fails makes the profile be read again
@Component
@RequiredArgsConstructor
public class ShellyGen2Driver implements LightDriver {

    private final ShellyRpcClient shellyRpcClient;
    private final Gen2Profiles gen2Profiles;

    @Override
    public Mono<Void> turn(final Light light, final boolean on) {
        final LightBinding.ShellyGen2 device = binding(light);
        return switch (light.kind()) {
            case RELAY -> shellyRpcClient.switchSet(device.address(), device.channel(), on);
            case DIMMER -> shellyRpcClient.lightSet(device.address(), device.channel(), on, null);
            case RGBW -> colored(device, component -> component == ColorComponent.RGB
                ? shellyRpcClient.rgbSet(device.address(), device.channel(), on, null, null)
                : shellyRpcClient.rgbwSet(device.address(), device.channel(), on, null, null, null));
        };
    }

    @Override
    public Mono<Void> brightness(final Light light, final int brightness) {
        final LightBinding.ShellyGen2 device = binding(light);
        return light.colors()
            ? colored(device, component -> component == ColorComponent.RGB
                ? shellyRpcClient.rgbSet(device.address(), device.channel(), true, null, brightness)
                : shellyRpcClient.rgbwSet(device.address(), device.channel(), true, null, null, brightness))
            : shellyRpcClient.lightSet(device.address(), device.channel(), true, brightness);
    }

    @Override
    public Mono<Void> color(final Light light, final Color color) {
        final LightBinding.ShellyGen2 device = binding(light);
        return colored(device, component -> {
            if (component == ColorComponent.RGB) {
                return color.white() == null
                    ? shellyRpcClient.rgbSet(device.address(), device.channel(), true, color.rgb(), color.brightness())
                    : Mono.error(LightException.unsupported(light, "white - the device is in the profile rgb"));
            }
            return shellyRpcClient.rgbwSet(
                device.address(), device.channel(), true, color.rgb(), color.white(), color.brightness());
        });
    }

    private Mono<Void> colored(final LightBinding.ShellyGen2 device, final Function<ColorComponent, Mono<Void>> command) {
        return gen2Profiles.of(device)
            .flatMap(command)
            .doOnError(e -> {
                if (!(e instanceof LightException)) {
                    gen2Profiles.forget(device);
                }
            });
    }

    private static LightBinding.ShellyGen2 binding(final Light light) {
        return (LightBinding.ShellyGen2) light.binding();
    }
}
