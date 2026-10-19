package cloud.cholewa.light.driver;

import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.error.LightError;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Color;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.shelly.http.ShellyCallException;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.Gen1LightStatus;
import cloud.cholewa.shelly.model.Relay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShellyDriversTest {

    private static final String GEN1 = "10.0.0.21";
    private static final String GEN2 = "10.0.0.22";

    private final ShellyGen1Client gen1Client = mock(ShellyGen1Client.class);
    private final ShellyRpcClient rpcClient = mock(ShellyRpcClient.class);
    private final ShellyGen1Driver gen1Driver = new ShellyGen1Driver(gen1Client);
    private final ShellyGen2Driver gen2Driver = new ShellyGen2Driver(rpcClient);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void should_switch_a_gen1_relay(final boolean on) {
        when(gen1Client.relay(GEN1, 0, on)).thenReturn(Mono.just(new Relay()));

        gen1Driver.turn(gen1(LightKind.RELAY), on).as(StepVerifier::create).verifyComplete();

        verify(gen1Client).relay(GEN1, 0, on);
    }

    //a dimmer, a Duo, a bulb or an RGBW2 in the mode white
    @Test
    void should_switch_and_dim_a_gen1_light() {
        when(gen1Client.light(GEN1, 0, false, null)).thenReturn(Mono.just(new Gen1LightStatus()));
        when(gen1Client.light(GEN1, 0, true, 35)).thenReturn(Mono.just(new Gen1LightStatus()));

        gen1Driver.turn(gen1(LightKind.DIMMER), false).as(StepVerifier::create).verifyComplete();
        gen1Driver.brightness(gen1(LightKind.DIMMER), 35).as(StepVerifier::create).verifyComplete();

        verify(gen1Client).light(GEN1, 0, false, null);
        verify(gen1Client).light(GEN1, 0, true, 35);
    }

    //the mode color has no brightness - the gain is its level
    @Test
    void should_drive_a_gen1_rgbw_light_through_color_with_the_brightness_as_the_gain() {
        when(gen1Client.color(anyString(), anyInt(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(new Gen1LightStatus()));

        gen1Driver.turn(gen1(LightKind.RGBW), true).as(StepVerifier::create).verifyComplete();
        gen1Driver.brightness(gen1(LightKind.RGBW), 60).as(StepVerifier::create).verifyComplete();
        gen1Driver.color(gen1(LightKind.RGBW), new Color(List.of(255, 128, 0), 10, 80)).as(StepVerifier::create)
            .verifyComplete();
        gen1Driver.color(gen1(LightKind.RGBW), new Color(null, 200, null)).as(StepVerifier::create).verifyComplete();

        verify(gen1Client).color(GEN1, 0, true, null, null, null, null, null);
        verify(gen1Client).color(GEN1, 0, true, null, null, null, null, 60);
        verify(gen1Client).color(GEN1, 0, true, 255, 128, 0, 10, 80);
        verify(gen1Client).color(GEN1, 0, true, null, null, null, 200, null);
    }

    @Test
    void should_fail_with_the_gen1_device() {
        when(gen1Client.relay(GEN1, 0, true)).thenReturn(Mono.error(new ShellyCallException("GET /relay/0 of 10.0.0.21 timed out")));

        gen1Driver.turn(gen1(LightKind.RELAY), true).as(StepVerifier::create).expectError(ShellyCallException.class).verify();
    }

    @Test
    void should_switch_a_gen2_relay_and_a_gen2_dimmer() {
        when(rpcClient.switchSet(GEN2, 1, true)).thenReturn(Mono.empty());
        when(rpcClient.lightSet(GEN2, 1, false, null)).thenReturn(Mono.empty());
        when(rpcClient.lightSet(GEN2, 1, true, 45)).thenReturn(Mono.empty());

        gen2Driver.turn(gen2(LightKind.RELAY), true).as(StepVerifier::create).verifyComplete();
        gen2Driver.turn(gen2(LightKind.DIMMER), false).as(StepVerifier::create).verifyComplete();
        gen2Driver.brightness(gen2(LightKind.DIMMER), 45).as(StepVerifier::create).verifyComplete();

        verify(rpcClient).switchSet(GEN2, 1, true);
        verify(rpcClient).lightSet(GEN2, 1, false, null);
        verify(rpcClient).lightSet(GEN2, 1, true, 45);
    }

    //both Plus RGBW PM of the house run in the profile rgb: RGB.Set, read once from the device
    @Test
    void should_drive_a_gen2_device_in_the_profile_rgb_through_rgb_set() {
        when(rpcClient.status(GEN2)).thenReturn(Mono.just(Map.of("rgb:1", Map.of(), "sys", Map.of())));
        when(rpcClient.rgbSet(anyString(), anyInt(), any(), any(), any())).thenReturn(Mono.empty());

        gen2Driver.turn(gen2(LightKind.RGBW), true).as(StepVerifier::create).verifyComplete();
        gen2Driver.brightness(gen2(LightKind.RGBW), 70).as(StepVerifier::create).verifyComplete();
        gen2Driver.color(gen2(LightKind.RGBW), new Color(List.of(0, 0, 255), null, 50)).as(StepVerifier::create)
            .verifyComplete();

        verify(rpcClient).rgbSet(GEN2, 1, true, null, null);
        verify(rpcClient).rgbSet(GEN2, 1, true, null, 70);
        verify(rpcClient).rgbSet(GEN2, 1, true, List.of(0, 0, 255), 50);
        verify(rpcClient, times(1)).status(GEN2);
        verify(rpcClient, never()).rgbwSet(anyString(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void should_drive_a_gen2_device_in_the_profile_rgbw_through_rgbw_set() {
        when(rpcClient.status(GEN2)).thenReturn(Mono.just(Map.of("rgbw:1", Map.of())));
        when(rpcClient.rgbwSet(anyString(), anyInt(), any(), any(), any(), any())).thenReturn(Mono.empty());

        gen2Driver.turn(gen2(LightKind.RGBW), false).as(StepVerifier::create).verifyComplete();
        gen2Driver.color(gen2(LightKind.RGBW), new Color(List.of(10, 20, 30), 40, 50)).as(StepVerifier::create)
            .verifyComplete();

        verify(rpcClient).rgbwSet(GEN2, 1, false, null, null, null);
        verify(rpcClient).rgbwSet(GEN2, 1, true, List.of(10, 20, 30), 40, 50);
    }

    //the profile rgb has no white channel: the caller is told, nothing is sent
    @Test
    void should_refuse_white_for_a_gen2_device_in_the_profile_rgb() {
        when(rpcClient.status(GEN2)).thenReturn(Mono.just(Map.of("rgb:1", Map.of())));

        gen2Driver.color(gen2(LightKind.RGBW), new Color(null, 100, null)).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((LightException) e).getError()).isEqualTo(LightError.UNSUPPORTED_CAPABILITY))
            .verify();

        verify(rpcClient, never()).rgbSet(anyString(), anyInt(), any(), any(), any());
    }

    //a device switched to the other profile is read again after the command that failed on it
    @Test
    void should_read_the_profile_again_after_a_failed_command() {
        when(rpcClient.status(GEN2))
            .thenReturn(Mono.just(Map.of("rgb:1", Map.of())))
            .thenReturn(Mono.just(Map.of("rgbw:1", Map.of())));
        when(rpcClient.rgbSet(anyString(), anyInt(), any(), any(), any()))
            .thenReturn(Mono.error(new ShellyCallException("RGB.Set of 10.0.0.22 answered -105")));
        when(rpcClient.rgbwSet(anyString(), anyInt(), any(), any(), any(), any())).thenReturn(Mono.empty());

        gen2Driver.turn(gen2(LightKind.RGBW), true).as(StepVerifier::create).expectError(ShellyCallException.class).verify();
        gen2Driver.turn(gen2(LightKind.RGBW), true).as(StepVerifier::create).verifyComplete();

        verify(rpcClient, times(2)).status(GEN2);
        verify(rpcClient).rgbwSet(GEN2, 1, true, null, null, null);
    }

    //the registry calls it rgbw, the device has neither component: a failed command, never a guess
    @Test
    void should_fail_when_the_device_has_no_color_component() {
        when(rpcClient.status(GEN2)).thenReturn(Mono.just(Map.of("light:1", Map.of())));

        gen2Driver.turn(gen2(LightKind.RGBW), true).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalStateException.class).hasMessageContaining("rgb:1"))
            .verify();

        verify(rpcClient, never()).rgbSet(anyString(), anyInt(), any(), any(), any());
        verify(rpcClient, never()).rgbwSet(anyString(), anyInt(), any(), any(), any(), any());
        verify(rpcClient, never()).lightSet(anyString(), anyInt(), anyBoolean(), any());
    }

    private static Light gen1(final LightKind kind) {
        return new Light(70, "Sanctum ambient", RoomName.SANCTUM, SmartVendor.SHELLY, new LightBinding.ShellyGen1(GEN1, 0), kind);
    }

    private static Light gen2(final LightKind kind) {
        return new Light(71, "Hall up left", RoomName.HALL_UP, SmartVendor.SHELLY, new LightBinding.ShellyGen2(GEN2, 1), kind);
    }
}
