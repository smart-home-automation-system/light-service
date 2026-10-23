package cloud.cholewa.light.state;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.light.driver.EatonDriver;
import cloud.cholewa.light.driver.EatonDriver.AmxCallException;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.registry.LightRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

//An Eaton actuator reports only when it is switched, and nothing at all while the AMX is away: what is kept of
//it is believed only once it was heard after the start (LightStateTracker.isConfirmed, HAS-308). So every Eaton
//light not confirmed yet is asked for its status (amx-service sends the 0B of HAS-259) - a minute after the start
//(the device queue is consumed by then, so no answer is lost) and again every five minutes until it answers; the
//answer is the usual status event and reaches the state through LightEventRouter. One light after the other, a
//second apart: amx-service answers once the frame went out, and the answers of eighteen actuators at once would
//collide on the air.
//When every Eaton light is confirmed, one of them in turn is asked all the same: a probe of the link, the only
//way to learn that the AMX went away while nobody switched anything - its FRAME_SERVER_UNAVAILABLE makes every
//Eaton light unknown (EatonDriver), and the pass stops there: the others would be refused the same way. A light
//that cannot be asked for another reason is a WARN; it is asked again with the next pass. Off under the test
//profile
@Slf4j
@Component
@RequiredArgsConstructor
public class EatonStatusRequests {

    private static final Duration PAUSE = Duration.ofSeconds(1);
    static final Duration LIMIT = Duration.ofMinutes(5);

    private final LightRegistry lightRegistry;
    private final EatonDriver eatonDriver;
    private final LightStateTracker lightStateTracker;

    //the light the next probe asks, in turn
    private final AtomicInteger probe = new AtomicInteger();

    //Bounded (HAS-302): the Eaton lights one after the other, a second apart, each at most the connect and
    //response timeouts of amx-service - about four minutes if none answers, some twenty seconds when the
    //frame server is there. A pass ended at the bound leaves the lights it never got to for the next one
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT5M")
    Mono<Void> askUnconfirmed() {
        return ScheduledPass.bounded("light Eaton status requests", LIMIT, this::askAll);
    }

    Mono<Void> askAll() {
        return Mono.defer(() -> {
            final List<Light> eaton = lightRegistry.current().stream()
                .filter(light -> light.binding() instanceof LightBinding.Eaton)
                .toList();
            if (eaton.isEmpty()) {
                return Mono.empty();
            }
            final List<Light> unconfirmed = eaton.stream().filter(light -> !lightStateTracker.isConfirmed(light)).toList();
            final List<Light> asked;
            if (unconfirmed.isEmpty()) {
                asked = List.of(eaton.get(Math.floorMod(probe.getAndIncrement(), eaton.size())));
            } else {
                log.info("Asking {} Eaton lights not confirmed since the start or the loss of the AMX for their status",
                    unconfirmed.size());
                asked = unconfirmed;
            }
            return Flux.fromIterable(asked)
                .concatMap(light -> ask(light).flatMap(goOn -> goOn ? Mono.delay(PAUSE).thenReturn(true) : Mono.just(false)))
                .takeUntil(goOn -> !goOn)
                .then();
        });
    }

    //true: the next light may be asked; false: the frame server is away, the others would be refused alike
    private Mono<Boolean> ask(final Light light) {
        return eatonDriver.requestStatus(light)
            .thenReturn(true)
            .onErrorResume(e -> {
                if (e instanceof AmxCallException failure && failure.frameServerUnavailable()) {
                    log.info("Light {} ({}) could not be asked for its status: the AMX frame server is not available",
                        light.id(), light.name());
                    return Mono.just(false);
                }
                log.warn("Light {} ({}) could not be asked for its status: {}", light.id(), light.name(), e.toString());
                return Mono.just(true);
            });
    }
}
