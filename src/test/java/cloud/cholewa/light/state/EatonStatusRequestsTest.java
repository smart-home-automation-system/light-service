package cloud.cholewa.light.state;

import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.driver.EatonDriver;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.registry.LightRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EatonStatusRequestsTest {

    private static final Light BOILER = new Light(35, "Boiler", RoomName.BOILER, SmartVendor.EATON,
        new LightBinding.Eaton(35, EatonGatewayType.LIGHTS), LightKind.RELAY);
    private static final Light STAIRS = new Light(45, "Stairs", RoomName.STAIRS, SmartVendor.EATON,
        new LightBinding.Eaton(21, EatonGatewayType.LIGHTS), LightKind.DIMMER);
    private static final Light MAIN = new Light(87, "Main", RoomName.OFFICE, SmartVendor.SHELLY,
        new LightBinding.ShellyGen2("10.0.0.45", 0, "a"), LightKind.RELAY);

    private final LightRegistry registry = mock(LightRegistry.class);
    private final EatonDriver driver = mock(EatonDriver.class);
    private final LightStateTracker tracker = mock(LightStateTracker.class);
    private final EatonStatusRequests requests = new EatonStatusRequests(registry, driver, tracker);

    //one after the other, a second apart; one that cannot be asked holds up none after it
    @Test
    void should_ask_every_unconfirmed_eaton_light_one_after_the_other() {
        when(registry.current()).thenReturn(List.of(BOILER, MAIN, STAIRS));
        when(driver.requestStatus(BOILER)).thenReturn(Mono.error(new IllegalStateException("amx-service answered 500")));
        when(driver.requestStatus(STAIRS)).thenReturn(Mono.empty());

        StepVerifier.withVirtualTime(requests::askUnconfirmed)
            .thenAwait(Duration.ofSeconds(5))
            .verifyComplete();

        final InOrder order = inOrder(driver);
        order.verify(driver).requestStatus(BOILER);
        order.verify(driver).requestStatus(STAIRS);
        verify(driver, times(2)).requestStatus(any());
    }

    //HAS-308: a light that did not answer is asked again with the next pass, one that answered is not
    @Test
    void should_ask_again_until_the_light_answers() {
        when(registry.current()).thenReturn(List.of(BOILER, STAIRS));
        when(driver.requestStatus(any())).thenReturn(Mono.empty());
        when(tracker.isConfirmed(STAIRS)).thenReturn(true);

        StepVerifier.withVirtualTime(requests::askUnconfirmed).thenAwait(Duration.ofSeconds(5)).verifyComplete();
        StepVerifier.withVirtualTime(requests::askUnconfirmed).thenAwait(Duration.ofSeconds(5)).verifyComplete();

        verify(driver, times(2)).requestStatus(BOILER);
        verify(driver, never()).requestStatus(STAIRS);
    }

    //every Eaton light confirmed: one of them in turn is asked, the probe that finds the AMX away
    @Test
    void should_probe_one_light_in_turn_when_every_one_is_confirmed() {
        when(registry.current()).thenReturn(List.of(BOILER, MAIN, STAIRS));
        when(driver.requestStatus(any())).thenReturn(Mono.empty());
        when(tracker.isConfirmed(any())).thenReturn(true);

        StepVerifier.withVirtualTime(requests::askUnconfirmed).thenAwait(Duration.ofSeconds(5)).verifyComplete();
        StepVerifier.withVirtualTime(requests::askUnconfirmed).thenAwait(Duration.ofSeconds(5)).verifyComplete();
        StepVerifier.withVirtualTime(requests::askUnconfirmed).thenAwait(Duration.ofSeconds(5)).verifyComplete();

        final InOrder order = inOrder(driver);
        order.verify(driver).requestStatus(BOILER);
        order.verify(driver).requestStatus(STAIRS);
        order.verify(driver).requestStatus(BOILER);
        verify(driver, never()).requestStatus(MAIN);
    }

    //the frame server away: the others would be refused the same way - the pass ends there
    @Test
    void should_stop_the_pass_when_the_amx_is_away() {
        when(registry.current()).thenReturn(List.of(BOILER, STAIRS));
        final EatonDriver.AmxCallException away = amxAway();
        when(driver.requestStatus(BOILER)).thenReturn(Mono.error(away));

        StepVerifier.withVirtualTime(requests::askUnconfirmed).thenAwait(Duration.ofSeconds(5)).verifyComplete();

        verify(driver).requestStatus(BOILER);
        verify(driver, never()).requestStatus(STAIRS);
    }

    //a registry not read yet: nothing to ask, asked at the next pass
    @Test
    void should_wait_for_the_registry() {
        when(registry.current()).thenReturn(List.of());

        requests.askUnconfirmed().as(StepVerifier::create).verifyComplete();

        verify(driver, never()).requestStatus(any());
    }

    //HAS-302: a request amx-service never answers ends the pass at its bound, not the scheduler for good
    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(registry.current()).thenReturn(List.of(BOILER));
        when(driver.requestStatus(BOILER)).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(requests::askUnconfirmed)
            .expectSubscription()
            .expectNoEvent(EatonStatusRequests.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    private static EatonDriver.AmxCallException amxAway() {
        final EatonDriver.AmxCallException failure = mock(EatonDriver.AmxCallException.class);
        when(failure.frameServerUnavailable()).thenReturn(true);
        return failure;
    }
}
