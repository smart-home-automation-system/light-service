package cloud.cholewa.light.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.config.EatonProperties;
import cloud.cholewa.light.driver.EatonDriver;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.state.LightStateTracker;
import cloud.cholewa.light.state.LightStateTracker.IssuedCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The confirmation of a command to an Eaton light whose status did not arrive (HAS-295)
class EatonCommandConfirmationTest {

    private static final Duration CONFIRM_AFTER = Duration.ofSeconds(5);
    private static final Light CABINET = new Light(22, "Kitchen cabinet", RoomName.KITCHEN, SmartVendor.EATON,
        new LightBinding.Eaton(28, EatonGatewayType.LIGHTS), LightKind.RELAY);
    private static final Light SHELLY = new Light(105, "Lamp", RoomName.KITCHEN, SmartVendor.SHELLY,
        new LightBinding.ShellyGen1("10.0.0.1", 0), LightKind.RELAY);

    private final LightStateTracker tracker = mock(LightStateTracker.class);
    private final EatonDriver driver = mock(EatonDriver.class);
    private final IssuedCommand issued = mock(IssuedCommand.class);
    private final EatonCommandConfirmation confirmation =
        new EatonCommandConfirmation(tracker, driver, new EatonProperties(CONFIRM_AFTER));
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeEach
    void setUp() {
        log.start();
        ((Logger) LoggerFactory.getLogger(EatonCommandConfirmation.class)).addAppender(log);
        when(driver.requestStatus(CABINET)).thenReturn(Mono.empty());
        when(issued.source()).thenReturn("rule");
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(EatonCommandConfirmation.class)).detachAppender(log);
    }

    //asked once, confirm-after after the command - noted as asked before the request goes out
    @Test
    void should_ask_for_the_status_once_when_no_change_was_reported_after_confirm_after() {
        when(tracker.awaitingChange(CABINET, issued)).thenReturn(true, false);

        StepVerifier.withVirtualTime(() -> confirmation.confirm(CABINET, issued))
            .thenAwait(CONFIRM_AFTER.minusMillis(1))
            .then(() -> verify(driver, never()).requestStatus(any()))
            .thenAwait(Duration.ofMillis(1))
            .then(() -> {
                final InOrder order = inOrder(tracker, driver);
                order.verify(tracker).asked(issued);
                order.verify(driver).requestStatus(CABINET);
            })
            .thenAwait(CONFIRM_AFTER)
            .verifyComplete();

        verify(driver, times(1)).requestStatus(any());
        //the answer brought the change: nothing to warn about
        assertThat(warnings()).isEmpty();
    }

    @Test
    void should_not_ask_when_the_change_was_reported() {
        when(tracker.awaitingChange(CABINET, issued)).thenReturn(false);

        StepVerifier.withVirtualTime(() -> confirmation.confirm(CABINET, issued))
            .thenAwait(CONFIRM_AFTER.multipliedBy(2))
            .verifyComplete();

        verify(driver, never()).requestStatus(any());
        verify(tracker, never()).asked(any());
        assertThat(warnings()).isEmpty();
    }

    //the kitchen cabinet of 2026-10-20 had it switched: here it answers with the state from before
    @Test
    void should_warn_about_a_command_that_did_not_take() {
        when(tracker.awaitingChange(CABINET, issued)).thenReturn(true);
        when(tracker.notTaken(CABINET, issued)).thenReturn(true);

        StepVerifier.withVirtualTime(() -> confirmation.confirm(CABINET, issued))
            .thenAwait(CONFIRM_AFTER.multipliedBy(2))
            .verifyComplete();

        assertThat(warnings()).containsExactly("Light 22 (Kitchen cabinet) answered the status request with its state "
            + "from before the command of rule: the command did not take");
    }

    @Test
    void should_warn_about_a_status_request_nobody_answered() {
        when(tracker.awaitingChange(CABINET, issued)).thenReturn(true);
        when(tracker.notTaken(CABINET, issued)).thenReturn(false);

        StepVerifier.withVirtualTime(() -> confirmation.confirm(CABINET, issued))
            .thenAwait(CONFIRM_AFTER.multipliedBy(2).minusMillis(1))
            .then(() -> assertThat(warnings()).isEmpty())
            .thenAwait(Duration.ofMillis(1))
            .verifyComplete();

        assertThat(warnings()).containsExactly("Light 22 (Kitchen cabinet) did not answer the status request within "
            + "PT5S: the command of rule is unconfirmed, the state left as it is");
    }

    //a request that fails is a WARN and nothing more - never repeated
    @Test
    void should_end_after_a_status_request_that_failed() {
        when(tracker.awaitingChange(CABINET, issued)).thenReturn(true);
        when(driver.requestStatus(CABINET)).thenReturn(Mono.error(new IllegalStateException("amx-service answered 502")));

        StepVerifier.withVirtualTime(() -> confirmation.confirm(CABINET, issued))
            .thenAwait(CONFIRM_AFTER.multipliedBy(3))
            .verifyComplete();

        verify(driver, times(1)).requestStatus(CABINET);
        verify(tracker, never()).notTaken(any(), any());
        assertThat(warnings()).containsExactly(
            "The status of light 22 (Kitchen cabinet) could not be asked: java.lang.IllegalStateException: amx-service answered 502");
    }

    //every command its own wait: the confirmation of one is no state of the next
    @Test
    void should_confirm_every_command_anew() {
        when(tracker.awaitingChange(CABINET, issued)).thenReturn(true);
        when(tracker.notTaken(CABINET, issued)).thenReturn(true);

        StepVerifier.withVirtualTime(() -> confirmation.confirm(CABINET, issued))
            .thenAwait(CONFIRM_AFTER.multipliedBy(2))
            .verifyComplete();
        StepVerifier.withVirtualTime(() -> confirmation.confirm(CABINET, issued))
            .thenAwait(CONFIRM_AFTER.multipliedBy(2))
            .verifyComplete();

        verify(driver, times(2)).requestStatus(CABINET);
        assertThat(warnings()).hasSize(2);
    }

    //a Shelly light reports over MQTT or the poll: nothing to wait for, nothing scheduled
    @Test
    void should_start_nothing_for_a_light_that_is_not_eaton() {
        confirmation.start(SHELLY, issued);

        verify(tracker, never()).awaitingChange(any(), any());
    }

    private List<String> warnings() {
        return log.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }
}
