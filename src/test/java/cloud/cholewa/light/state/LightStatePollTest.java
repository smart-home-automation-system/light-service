package cloud.cholewa.light.state;

import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.driver.Gen2Profiles;
import cloud.cholewa.light.driver.Gen2Profiles.ColorComponent;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.model.LightReading;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.shelly.http.ShellyCallException;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.Gen1DeviceStatus;
import cloud.cholewa.shelly.model.Gen1LightStatus;
import cloud.cholewa.shelly.model.Gen2LightStatus;
import cloud.cholewa.shelly.model.Relay;
import cloud.cholewa.shelly.model.RgbwStatus;
import cloud.cholewa.shelly.model.SwitchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LightStatePollTest {

    private static final Light WALL_LEFT = light(90, LightKind.DIMMER, new LightBinding.ShellyGen1("10.0.0.24", 0));
    private static final Light LIVIA = light(91, LightKind.RELAY, new LightBinding.ShellyGen1("10.0.0.65", 0));
    private static final Light MAIN = light(87, LightKind.RELAY, new LightBinding.ShellyGen2("10.0.0.45", 0, "a"));
    private static final Light DIMMER = light(88, LightKind.DIMMER, new LightBinding.ShellyGen2("10.0.0.44", 0, "b"));
    private static final Light LEFT_COLOR = light(89, LightKind.RGBW, new LightBinding.ShellyGen2("10.0.0.34", 0, "c"));
    private static final Light BOILER = light(35, LightKind.RELAY, new LightBinding.Eaton(35, EatonGatewayType.LIGHTS));

    private final LightRegistry registry = mock(LightRegistry.class);
    private final LightStateTracker tracker = mock(LightStateTracker.class);
    private final ShellyGen1Client gen1Client = mock(ShellyGen1Client.class);
    private final ShellyRpcClient rpcClient = mock(ShellyRpcClient.class);
    private final Gen2Profiles profiles = mock(Gen2Profiles.class);
    private final LightStatePoll poll = new LightStatePoll(registry, tracker, gen1Client, rpcClient, profiles);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(List.of(WALL_LEFT, LIVIA, MAIN, DIMMER, LEFT_COLOR, BOILER));
        when(tracker.observe(any(), any())).thenReturn(Mono.empty());
        when(tracker.reachability(any(), anyBoolean(), any())).thenReturn(Mono.empty());
    }

    @Test
    void should_read_every_gen1_light_from_its_status() {
        when(gen1Client.status("10.0.0.24")).thenReturn(Mono.just(new Gen1DeviceStatus()
            .lights(List.of(new Gen1LightStatus().ison(true).brightness(30)))));
        when(gen1Client.status("10.0.0.65")).thenReturn(Mono.just(new Gen1DeviceStatus()
            .relays(List.of(new Relay().ison(false)))));

        poll.pollGen1().as(StepVerifier::create).verifyComplete();

        verify(tracker).observe(WALL_LEFT, new LightReading(true, 30, null, null, null));
        verify(tracker).observe(LIVIA, LightReading.switched(false));
        verify(rpcClient, never()).switchStatus(anyString(), any(Integer.class));
    }

    //the poll is all that is heard of a Gen1 light: one that does not answer is unreachable, the
    //others are read all the same
    @Test
    void should_make_a_gen1_light_that_does_not_answer_unreachable() {
        when(gen1Client.status("10.0.0.24")).thenReturn(Mono.error(new ShellyCallException("timed out")));
        when(gen1Client.status("10.0.0.65")).thenReturn(Mono.just(new Gen1DeviceStatus()));

        poll.pollGen1().as(StepVerifier::create).verifyComplete();

        verify(tracker).reachability(eq(WALL_LEFT), eq(false), any());
        //a status without the relay of the light is no answer either
        verify(tracker).reachability(eq(LIVIA), eq(false), any());
    }

    @Test
    void should_reconcile_every_gen2_light_by_its_kind_and_profile() {
        when(rpcClient.switchStatus("10.0.0.45", 0)).thenReturn(Mono.just(new SwitchStatus().output(true)));
        when(rpcClient.lightStatus("10.0.0.44", 0)).thenReturn(Mono.just(new Gen2LightStatus().output(false).brightness(80)));
        when(profiles.of((LightBinding.ShellyGen2) LEFT_COLOR.binding())).thenReturn(Mono.just(ColorComponent.RGB));
        when(rpcClient.rgbStatus("10.0.0.34", 0))
            .thenReturn(Mono.just(new RgbwStatus().output(false).rgb(List.of(0, 255, 0)).brightness(100)));

        poll.reconcileGen2().as(StepVerifier::create).verifyComplete();

        verify(tracker).observe(MAIN, LightReading.switched(true));
        verify(tracker).observe(DIMMER, new LightReading(false, 80, null, null, null));
        verify(tracker).observe(LEFT_COLOR, new LightReading(false, 100, List.of(0, 255, 0), null, null));
        verify(rpcClient, never()).rgbwStatus(anyString(), any(Integer.class));
    }

    //MQTT owns the reachability of a Gen2+ light: a failed call next to a live stream would make it flap
    @Test
    void should_leave_the_reachability_of_a_gen2_light_to_mqtt() {
        when(rpcClient.switchStatus("10.0.0.45", 0)).thenReturn(Mono.error(new ShellyCallException("timed out")));
        when(rpcClient.lightStatus("10.0.0.44", 0)).thenReturn(Mono.just(new Gen2LightStatus()));
        when(profiles.of(any())).thenReturn(Mono.error(new ShellyCallException("timed out")));

        poll.reconcileGen2().as(StepVerifier::create).verifyComplete();

        verify(tracker, never()).reachability(any(), anyBoolean(), any());
        verify(tracker, never()).observe(any(), any());
        verify(profiles).forget((LightBinding.ShellyGen2) LEFT_COLOR.binding());
    }

    //a store that failed is not a device that did not answer
    @Test
    void should_not_make_a_light_unreachable_when_its_state_could_not_be_stored() {
        when(gen1Client.status("10.0.0.24")).thenReturn(Mono.just(new Gen1DeviceStatus()
            .lights(List.of(new Gen1LightStatus().ison(true)))));
        when(gen1Client.status("10.0.0.65")).thenReturn(Mono.just(new Gen1DeviceStatus()
            .relays(List.of(new Relay().ison(true)))));
        when(tracker.observe(any(), any())).thenReturn(Mono.error(new IllegalStateException("database away")));

        poll.pollGen1().as(StepVerifier::create).verifyComplete();

        verify(tracker, never()).reachability(any(), anyBoolean(), any());
    }

    //Spring subscribes to the Mono of the schedule again for every run: the devices are asked each time
    @Test
    void should_read_the_devices_again_on_every_subscription() {
        when(gen1Client.status(anyString())).thenReturn(Mono.just(new Gen1DeviceStatus()));
        final Mono<Void> pass = poll.pollGen1();

        pass.as(StepVerifier::create).verifyComplete();
        pass.as(StepVerifier::create).verifyComplete();

        verify(gen1Client, times(2)).status("10.0.0.24");
    }

    private static Light light(final long id, final LightKind kind, final LightBinding binding) {
        return new Light(id, "Light " + id, RoomName.OFFICE,
            binding instanceof LightBinding.Eaton ? SmartVendor.EATON : SmartVendor.SHELLY, binding, kind);
    }
}
