package cloud.cholewa.light.registry;

import cloud.cholewa.home.model.EatonBinding;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;

import java.util.Optional;

//A device of the registry as a light - or nothing, when the registry holds too little to drive it:
//no id or room, no light kind (what it can do would be a guess), a Shelly without its address or
//generation, an Eaton device without its point or with a color it cannot have, a vendor the service
//has no driver for. The caller skips it and names it at WARN
final class LightMapper {

    private LightMapper() {
    }

    static Optional<Light> toLight(final SmartDevice device) {
        if (device.getId() == null || device.getRoom() == null || device.getVendor() == null
            || device.getLightKind() == null) {
            return Optional.empty();
        }

        return binding(device).map(binding -> new Light(
            device.getId(),
            device.getName(),
            device.getRoom(),
            device.getVendor(),
            binding,
            device.getLightKind()
        ));
    }

    private static Optional<LightBinding> binding(final SmartDevice device) {
        if (device.getVendor() == SmartVendor.SHELLY) {
            return shelly(device.getShelly());
        }
        if (device.getVendor() == SmartVendor.EATON && device.getLightKind() != LightKind.RGBW) {
            return eaton(device.getEaton());
        }
        return Optional.empty();
    }

    //a device with one output leaves the channel out: it is 0
    private static Optional<LightBinding> shelly(final ShellyBinding shelly) {
        if (shelly == null || shelly.getAddress() == null || shelly.getAddress().isBlank()
            || shelly.getGeneration() == null) {
            return Optional.empty();
        }

        final int channel = shelly.getChannel() == null ? 0 : shelly.getChannel();

        return Optional.of(shelly.getGeneration() >= 2
            ? new LightBinding.ShellyGen2(shelly.getAddress(), channel, shelly.getDeviceId())
            : new LightBinding.ShellyGen1(shelly.getAddress(), channel));
    }

    //a command names no gateway - amx-service finds it in the same registry by the point; the events
    //are told apart by it (eaton.<gateway>.<point>), so a light without one receives none
    private static Optional<LightBinding> eaton(final EatonBinding eaton) {
        if (eaton == null || eaton.getPoint() == null) {
            return Optional.empty();
        }
        return Optional.of(new LightBinding.Eaton(eaton.getPoint(), eaton.getGateway()));
    }
}
