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
import cloud.cholewa.shelly.codec.ShellyDecodeException;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//the routing key decides first, by the registry; the body of an event of another device is not read
class LightEventRouterTest {

    private static final Light BOILER = light(35, LightKind.RELAY, new LightBinding.Eaton(35, EatonGatewayType.LIGHTS));
    private static final Light STAIRS = light(45, LightKind.DIMMER, new LightBinding.Eaton(21, EatonGatewayType.LIGHTS));
    private static final Light MAIN = light(87, LightKind.RELAY,
        new LightBinding.ShellyGen2("10.0.0.45", 0, "shelly1pmg3-dcda0cde0b3c"));
    private static final Light DIMMER = light(88, LightKind.DIMMER,
        new LightBinding.ShellyGen2("10.0.0.44", 0, "shellydimmerg3-0892724f4e30"));
    private static final Light LEFT_COLOR = light(89, LightKind.RGBW,
        new LightBinding.ShellyGen2("10.0.0.34", 0, "shellyplusrgbwpm-30c922571bdc"));

    private final LightRegistry registry = mock(LightRegistry.class);
    private final LightStateTracker tracker = mock(LightStateTracker.class);
    private final Gen2Profiles profiles = mock(Gen2Profiles.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final LightEventRouter router =
        new LightEventRouter(registry, tracker, new ShellyMqttDecoder(jsonMapper), profiles, jsonMapper);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(List.of(BOILER, STAIRS, MAIN, DIMMER, LEFT_COLOR));
        when(tracker.observe(any(), any())).thenReturn(Mono.empty());
        when(tracker.reachability(any(), anyBoolean(), any())).thenReturn(Mono.empty());
    }

    @Test
    void should_take_the_status_of_an_eaton_relay_as_on_or_off() {
        route("eaton.lights.35", eaton("lights", 35, "status", 1));

        verify(tracker).observe(BOILER, LightReading.switched(true));
    }

    @Test
    void should_take_the_status_of_an_eaton_dimmer_as_its_level() {
        route("eaton.lights.21", eaton("lights", 21, "status", 80));

        verify(tracker).observe(STAIRS, new LightReading(true, 80, null, null, null));
    }

    //a blind on the same point of the other gateway is not the light
    @Test
    void should_skip_an_eaton_event_of_the_other_gateway() {
        route("eaton.blinds.35", eaton("blinds", 35, "status", 1));

        verifyNoInteractions(tracker);
    }

    //a wall push button is not the actuator - it reports its own status after
    @Test
    void should_skip_an_eaton_event_that_is_not_a_status() {
        route("eaton.lights.35", """
            {"at":"2026-10-19T19:00:00","vendor":"eaton","gateway":"lights","point":35,"event":"push button on"}""");

        verifyNoInteractions(tracker);
    }

    @Test
    void should_skip_a_status_that_is_none_of_a_relay() {
        route("eaton.lights.35", eaton("lights", 35, "status", 7));

        verifyNoInteractions(tracker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eaton.lights.99", "eaton.lights.", "eaton.lights.3x", "eaton.lights.123", "eaton.lights"})
    void should_not_read_an_event_of_a_point_without_a_light(final String routingKey) {
        route(routingKey, "not json");

        verifyNoInteractions(tracker);
    }

    @Test
    void should_take_the_status_of_a_gen2_relay_whatever_the_case_of_its_id() {
        route("SHELLY1PMG3-DCDA0CDE0B3C.status.switch:0", """
            {"id":0,"source":"button","output":true,"apower":12.5}""");

        verify(tracker).observe(MAIN, new LightReading(true, null, null, null, "button"));
    }

    @Test
    void should_take_the_status_of_a_gen2_dimmer() {
        route("shellydimmerg3-0892724f4e30.status.light:0", """
            {"id":0,"source":"switch","output":true,"brightness":40}""");

        verify(tracker).observe(DIMMER, new LightReading(true, 40, null, null, "switch"));
    }

    //the topic says the profile of the device: the commands learn it for free
    @Test
    void should_take_the_status_of_a_gen2_rgb_component_and_learn_the_profile() {
        route("shellyplusrgbwpm-30c922571bdc.status.rgb:0", """
            {"id":0,"source":"init","output":false,"rgb":[0,255,0],"brightness":100}""");

        verify(tracker).observe(LEFT_COLOR, new LightReading(false, 100, List.of(0, 255, 0), null, "init"));
        verify(profiles).seen((LightBinding.ShellyGen2) LEFT_COLOR.binding(), ColorComponent.RGB);
    }

    //the component of another kind, another channel, a sensor of the device: not the light
    @ParameterizedTest
    @ValueSource(strings = {
        "shelly1pmg3-dcda0cde0b3c.status.light:0",
        "shelly1pmg3-dcda0cde0b3c.status.switch:1",
        "shelly1pmg3-dcda0cde0b3c.status.sys",
        "shellyplusplugs-d48afc58da2c.status.switch:0"
    })
    void should_skip_a_status_that_is_not_the_light(final String routingKey) {
        route(routingKey, "{\"id\":0,\"output\":true}");

        verifyNoInteractions(tracker);
    }

    @Test
    void should_take_the_last_will_of_a_device_as_unreachable() {
        route("shelly1pmg3-dcda0cde0b3c.online", "false");

        verify(tracker).reachability(eq(MAIN), eq(false), any());
    }

    //a body that is not what its topic carries is an error for the consumer to log
    @Test
    void should_fail_on_a_body_that_is_not_its_status() {
        router.route("shellydimmerg3-0892724f4e30.status.light:0", "[1,2]".getBytes(StandardCharsets.UTF_8))
            .as(StepVerifier::create)
            .expectError(ShellyDecodeException.class)
            .verify();
    }

    private void route(final String routingKey, final String body) {
        router.route(routingKey, body.getBytes(StandardCharsets.UTF_8)).as(StepVerifier::create).verifyComplete();
    }

    private static String eaton(final String gateway, final int point, final String event, final int value) {
        return """
            {"at":"2026-10-19T19:00:00","vendor":"eaton","gateway":"%s","point":%d,"event":"%s","value":%d}"""
            .formatted(gateway, point, event, value);
    }

    private static Light light(final long id, final LightKind kind, final LightBinding binding) {
        return new Light(id, "Light " + id, RoomName.OFFICE,
            binding instanceof LightBinding.Eaton ? SmartVendor.EATON : SmartVendor.SHELLY, binding, kind);
    }
}
