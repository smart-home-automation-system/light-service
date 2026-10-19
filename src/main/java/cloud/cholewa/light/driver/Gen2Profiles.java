package cloud.cholewa.light.driver;

import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

//Which color component a Gen2+ rgbw light has - the profile of the device, not the registry: a Plus
//RGBW PM in the profile rgb has the component rgb:<id> and no white channel (both of the house were in
//it on 2026-10-11), in the profile rgbw it has rgbw:<id>. Read from Shelly.GetStatus the first time it
//is needed and kept; a call that fails forgets it, so a device switched to the other profile is read
//again. Its MQTT status says it too (the component of the topic): that is learned for free
@Component
@RequiredArgsConstructor
public class Gen2Profiles {

    private final ShellyRpcClient shellyRpcClient;

    private final Map<LightBinding.ShellyGen2, ColorComponent> profiles = new ConcurrentHashMap<>();

    public Mono<ColorComponent> of(final LightBinding.ShellyGen2 device) {
        final ColorComponent known = profiles.get(device);
        if (known != null) {
            return Mono.just(known);
        }
        return shellyRpcClient.status(device.address())
            .flatMap(status -> {
                if (status.containsKey("rgb:" + device.channel())) {
                    return Mono.just(ColorComponent.RGB);
                }
                if (status.containsKey("rgbw:" + device.channel())) {
                    return Mono.just(ColorComponent.RGBW);
                }
                return Mono.error(new IllegalStateException("device " + device.address() + " has no component rgb:"
                    + device.channel() + " or rgbw:" + device.channel()));
            })
            .doOnNext(component -> profiles.put(device, component));
    }

    public void seen(final LightBinding.ShellyGen2 device, final ColorComponent component) {
        profiles.put(device, component);
    }

    public void forget(final LightBinding.ShellyGen2 device) {
        profiles.remove(device);
    }

    public enum ColorComponent { RGB, RGBW }
}
