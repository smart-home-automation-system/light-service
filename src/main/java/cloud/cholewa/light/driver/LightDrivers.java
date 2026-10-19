package cloud.cholewa.light.driver;

import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

//the driver of a light, by the kind of its binding; the binding is sealed, so a new kind does not
//compile until it has a driver here
@Component
@RequiredArgsConstructor
public class LightDrivers {

    private final ShellyGen1Driver shellyGen1Driver;
    private final ShellyGen2Driver shellyGen2Driver;
    private final EatonDriver eatonDriver;

    public LightDriver of(final Light light) {
        return switch (light.binding()) {
            case LightBinding.ShellyGen1 gen1 -> shellyGen1Driver;
            case LightBinding.ShellyGen2 gen2 -> shellyGen2Driver;
            case LightBinding.Eaton eaton -> eatonDriver;
        };
    }
}
