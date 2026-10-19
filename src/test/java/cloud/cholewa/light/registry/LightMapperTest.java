package cloud.cholewa.light.registry;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.EatonBinding;
import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class LightMapperTest {

    @Test
    void should_map_an_eaton_light_by_its_point() {
        assertThat(LightMapper.toLight(eaton(LightKind.DIMMER).eaton(new EatonBinding().gateway(EatonGatewayType.LIGHTS).point(24))))
            .contains(new Light(25, "Living room sofa", RoomName.LIVING_ROOM, SmartVendor.EATON,
                new LightBinding.Eaton(24), LightKind.DIMMER));
    }

    @Test
    void should_map_a_shelly_by_its_generation() {
        assertThat(LightMapper.toLight(shelly(2, 1, LightKind.RGBW)))
            .map(Light::binding)
            .contains(new LightBinding.ShellyGen2("10.0.0.34", 1));
        assertThat(LightMapper.toLight(shelly(1, 0, LightKind.DIMMER)))
            .map(Light::binding)
            .contains(new LightBinding.ShellyGen1("10.0.0.34", 0));
    }

    //a device with one output leaves the channel out
    @Test
    void should_take_channel_0_when_the_registry_leaves_it_out() {
        assertThat(LightMapper.toLight(shelly(2, null, LightKind.RELAY)))
            .map(Light::binding)
            .contains(new LightBinding.ShellyGen2("10.0.0.34", 0));
    }

    //what a light can do would be a guess - a brightness sent to a relay, a relay command to a dimmer
    @Test
    void should_skip_a_light_without_its_kind() {
        assertThat(LightMapper.toLight(shelly(2, 0, null))).isEmpty();
        assertThat(LightMapper.toLight(eaton(null))).isEmpty();
    }

    @Test
    void should_skip_an_eaton_light_with_a_color() {
        assertThat(LightMapper.toLight(eaton(LightKind.RGBW))).isEmpty();
    }

    @Test
    void should_skip_what_it_cannot_reach() {
        assertThat(LightMapper.toLight(eaton(LightKind.RELAY).eaton(new EatonBinding()))).isEmpty();
        assertThat(LightMapper.toLight(eaton(LightKind.RELAY).eaton(null))).isEmpty();
        assertThat(LightMapper.toLight(shelly(2, 0, LightKind.RELAY).shelly(new ShellyBinding().generation(2)))).isEmpty();
        assertThat(LightMapper.toLight(shelly(2, 0, LightKind.RELAY).shelly(new ShellyBinding().address(" ").generation(2))))
            .isEmpty();
        assertThat(LightMapper.toLight(shelly(2, 0, LightKind.RELAY).shelly(new ShellyBinding().address("10.0.0.34"))))
            .isEmpty();
        assertThat(LightMapper.toLight(eaton(LightKind.RELAY).room(null))).isEmpty();

        final SmartDevice withoutId = SmartDevice.builder()
            .name("Living room sofa")
            .room(RoomName.LIVING_ROOM)
            .kind(DeviceKind.LIGHT)
            .vendor(SmartVendor.EATON)
            .eaton(new EatonBinding().point(24))
            .lightKind(LightKind.RELAY)
            .build();
        assertThat(LightMapper.toLight(withoutId)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SmartVendor.class, mode = EnumSource.Mode.EXCLUDE, names = {"SHELLY", "EATON"})
    void should_skip_a_vendor_without_a_driver(final SmartVendor vendor) {
        assertThat(LightMapper.toLight(eaton(LightKind.RELAY).vendor(vendor))).isEmpty();
    }

    private static SmartDevice eaton(final LightKind kind) {
        return SmartDevice.builder()
            .id(25L)
            .name("Living room sofa")
            .room(RoomName.LIVING_ROOM)
            .kind(DeviceKind.LIGHT)
            .enabled(true)
            .vendor(SmartVendor.EATON)
            .eaton(new EatonBinding().gateway(EatonGatewayType.LIGHTS).point(24))
            .lightKind(kind)
            .build();
    }

    private static SmartDevice shelly(final int generation, final Integer channel, final LightKind kind) {
        return SmartDevice.builder()
            .id(60L)
            .name("Hall up left")
            .room(RoomName.HALL_UP)
            .kind(DeviceKind.LIGHT)
            .enabled(true)
            .vendor(SmartVendor.SHELLY)
            .shelly(new ShellyBinding().address("10.0.0.34").generation(generation).channel(channel))
            .lightKind(kind)
            .build();
    }
}
