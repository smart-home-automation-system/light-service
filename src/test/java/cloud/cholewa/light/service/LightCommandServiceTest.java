package cloud.cholewa.light.service;

import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.driver.LightDriver;
import cloud.cholewa.light.driver.LightDrivers;
import cloud.cholewa.light.error.LightError;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Color;
import cloud.cholewa.light.model.CommandSource;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.model.RoomCommandReply.LightOutcome;
import cloud.cholewa.light.model.Turn;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.light.state.LightStateTracker;
import cloud.cholewa.shelly.http.ShellyCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LightCommandServiceTest {

    private static final Light CEILING = light(21, RoomName.KITCHEN, LightKind.RELAY);
    private static final Light CABINET = light(22, RoomName.KITCHEN, LightKind.DIMMER);
    private static final Light BAR = light(23, RoomName.KITCHEN, LightKind.RGBW);
    private static final Color RED = new Color(List.of(255, 0, 0), null, 80);

    private final LightRegistry registry = mock(LightRegistry.class);
    private final LightDrivers drivers = mock(LightDrivers.class);
    private final LightDriver driver = mock(LightDriver.class);
    private final LightStateTracker tracker = mock(LightStateTracker.class);
    private final LightCommandService service = new LightCommandService(registry, drivers, tracker);

    @BeforeEach
    void setUp() {
        when(drivers.of(any())).thenReturn(driver);
        when(registry.find(21)).thenReturn(Mono.just(CEILING));
        when(registry.find(22)).thenReturn(Mono.just(CABINET));
        when(registry.find(23)).thenReturn(Mono.just(BAR));
    }

    @Test
    void should_switch_a_light_through_its_driver() {
        when(driver.turn(CEILING, false)).thenReturn(Mono.empty());

        service.turn(21, Turn.OFF, CommandSource.EMPTY_HOUSE).as(StepVerifier::create).verifyComplete();

        verify(driver).turn(CEILING, false);
    }

    //a device may report the change before it answers: the command is noted before the call, and kept
    //when the call fails - it may have switched the light all the same
    @Test
    void should_note_the_command_for_the_tracker_before_the_device_is_called() {
        when(driver.turn(CEILING, true)).thenReturn(Mono.error(new ShellyCallException("timed out")));

        service.turn(21, Turn.ON, CommandSource.RULE).as(StepVerifier::create).expectError(LightException.class).verify();

        final InOrder order = inOrder(tracker, driver);
        order.verify(tracker).commanded(CEILING, CommandSource.RULE);
        order.verify(driver).turn(CEILING, true);
    }

    //a command refused for what the light cannot do never reaches the tracker
    @Test
    void should_not_note_a_refused_command() {
        service.brightness(21, 30, CommandSource.DASHBOARD).as(StepVerifier::create).expectError(LightException.class).verify();

        verify(tracker, never()).commanded(any(), any());
    }

    @Test
    void should_dim_a_dimmer_and_an_rgbw_light() {
        when(driver.brightness(any(), anyInt())).thenReturn(Mono.empty());

        service.brightness(22, 30, CommandSource.RULE).as(StepVerifier::create).verifyComplete();
        service.brightness(23, 60, CommandSource.DASHBOARD).as(StepVerifier::create).verifyComplete();

        verify(driver).brightness(CABINET, 30);
        verify(driver).brightness(BAR, 60);
    }

    @Test
    void should_send_a_color_to_an_rgbw_light() {
        when(driver.color(BAR, RED)).thenReturn(Mono.empty());

        service.color(23, RED, CommandSource.DASHBOARD).as(StepVerifier::create).verifyComplete();

        verify(driver).color(BAR, RED);
    }

    //what a light cannot do is the caller's mistake - the device is never asked
    @Test
    void should_refuse_a_brightness_for_a_relay_and_a_color_for_a_light_without_one() {
        service.brightness(21, 30, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectErrorSatisfies(e -> {
                final LightException exception = (LightException) e;
                assertThat(exception.getError()).isEqualTo(LightError.UNSUPPORTED_CAPABILITY);
                assertThat(exception.getHttpStatus().value()).isEqualTo(400);
            })
            .verify();
        service.color(22, RED, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((LightException) e).getError()).isEqualTo(LightError.UNSUPPORTED_CAPABILITY))
            .verify();

        verify(driver, never()).brightness(any(), anyInt());
        verify(driver, never()).color(any(), any());
    }

    @Test
    void should_answer_a_failed_device_as_device_failed_with_the_reason_for_the_log() {
        when(driver.turn(CEILING, true)).thenReturn(Mono.error(new ShellyCallException("GET /relay/0 of 10.0.0.1 timed out")));

        service.turn(21, Turn.ON, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectErrorSatisfies(e -> {
                final LightException exception = (LightException) e;
                assertThat(exception.getError()).isEqualTo(LightError.DEVICE_FAILED);
                assertThat(exception.getHttpStatus().value()).isEqualTo(502);
                assertThat(exception.getMessage()).contains("light 21", "timed out");
            })
            .verify();
    }

    //a refusal of the driver itself (white for a device in the profile rgb) stays what it is
    @Test
    void should_pass_a_refusal_of_the_driver_on() {
        when(driver.color(BAR, RED)).thenReturn(Mono.error(LightException.unsupported(BAR, "white")));

        service.color(23, RED, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((LightException) e).getError()).isEqualTo(LightError.UNSUPPORTED_CAPABILITY))
            .verify();
    }

    @Test
    void should_pass_an_unknown_light_on() {
        when(registry.find(99)).thenReturn(Mono.error(LightException.notFound("no light with id 99")));

        service.turn(99, Turn.ON, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((LightException) e).getError()).isEqualTo(LightError.NOT_FOUND_LIGHT))
            .verify();
    }

    //one after the other; the failed one is named and the rest still switch
    @Test
    void should_switch_every_light_of_the_room_and_name_the_ones_that_failed() {
        when(registry.inRoom(RoomName.KITCHEN)).thenReturn(Mono.just(List.of(CEILING, CABINET, BAR)));
        when(driver.turn(CEILING, false)).thenReturn(Mono.error(new ShellyCallException("refused")));
        when(driver.turn(CABINET, false)).thenReturn(Mono.empty());
        when(driver.turn(BAR, false)).thenReturn(Mono.empty());

        service.turnRoom(RoomName.KITCHEN, Turn.OFF, CommandSource.EMPTY_HOUSE).as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.room()).isEqualTo("kitchen");
                assertThat(reply.turn()).isEqualTo("off");
                assertThat(reply.switched()).containsExactly(
                    new LightOutcome(22, "Light 22", null), new LightOutcome(23, "Light 23", null));
                assertThat(reply.failed()).containsExactly(new LightOutcome(21, "Light 21", "DEVICE_FAILED"));
            })
            .verifyComplete();

        final InOrder order = inOrder(driver);
        order.verify(driver).turn(CEILING, false);
        order.verify(driver).turn(CABINET, false);
        order.verify(driver).turn(BAR, false);
    }

    //nothing to accept when nothing switched
    @Test
    void should_fail_the_room_as_device_failed_when_no_light_took_the_command() {
        when(registry.inRoom(RoomName.KITCHEN)).thenReturn(Mono.just(List.of(CEILING, CABINET)));
        when(driver.turn(any(), any(Boolean.class))).thenReturn(Mono.error(new ShellyCallException("timed out")));

        service.turnRoom(RoomName.KITCHEN, Turn.ON, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectErrorSatisfies(e -> {
                final LightException exception = (LightException) e;
                assertThat(exception.getError()).isEqualTo(LightError.DEVICE_FAILED);
                assertThat(exception.getHttpStatus().value()).isEqualTo(502);
            })
            .verify();
    }

    @Test
    void should_pass_a_room_without_lights_on() {
        when(registry.inRoom(RoomName.SAUNA)).thenReturn(Mono.error(LightException.notFound("no light in the room sauna")));

        service.turnRoom(RoomName.SAUNA, Turn.ON, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectError(LightException.class)
            .verify();
    }

    private static Light light(final long id, final RoomName room, final LightKind kind) {
        return new Light(id, "Light " + id, room, SmartVendor.SHELLY, new LightBinding.ShellyGen1("10.0.0.1", 0), kind);
    }
}
