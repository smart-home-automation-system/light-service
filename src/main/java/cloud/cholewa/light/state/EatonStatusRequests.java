package cloud.cholewa.light.state;

import cloud.cholewa.light.driver.EatonDriver;
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
import java.util.concurrent.atomic.AtomicBoolean;

//An Eaton actuator reports only when it is switched: one nobody touched since the start of the pod
//would stay unknown. So every Eaton light is asked for its status once (amx-service sends the 0B of
//HAS-259); the answer is the usual status event and reaches the state through LightEventRouter.
//One light after the other, a second apart: amx-service answers once the frame went out, and the
//answers of eighteen actuators at once would collide on the air. A light that cannot be asked is a WARN and
//stays as restored from the database; nothing is repeated. Done once, after the first pass that found
//the registry; the pass runs a minute after the start (the device queue is consumed by then, so no
//answer is lost), and again every 5 minutes until the registry was read. Off under the test profile
@Slf4j
@Component
@RequiredArgsConstructor
public class EatonStatusRequests {

    private static final Duration PAUSE = Duration.ofSeconds(1);

    private final LightRegistry lightRegistry;
    private final EatonDriver eatonDriver;

    private final AtomicBoolean done = new AtomicBoolean();

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT5M")
    Mono<Void> askOnce() {
        return Mono.defer(() -> {
            if (done.get() || lightRegistry.current().isEmpty()) {
                return Mono.empty();
            }
            done.set(true);
            final List<Light> eaton = lightRegistry.current().stream()
                .filter(light -> light.binding() instanceof LightBinding.Eaton)
                .toList();
            log.info("Asking {} Eaton lights for their status", eaton.size());
            return Flux.fromIterable(eaton)
                .concatMap(light -> ask(light).then(Mono.delay(PAUSE)).then())
                .then();
        });
    }

    private Mono<Void> ask(final Light light) {
        return eatonDriver.requestStatus(light)
            .onErrorResume(e -> {
                log.warn("Light {} ({}) could not be asked for its status: {}", light.id(), light.name(), e.toString());
                return Mono.empty();
            });
    }
}
