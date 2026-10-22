package cloud.cholewa.light.registry;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//The registry read at the start and every five minutes. Spring subscribes again to the same Mono
//for every run, which is why the read itself is deferred in LightRegistry.refresh(). fixedDelay:
//the next read waits for the last one to end. Scheduling is off under the test profile.
//Bounded (HAS-302): one call of database-service with a response timeout of seconds - a read that
//takes a minute is hung, and ends there; the last list stays
@Component
@RequiredArgsConstructor
public class LightRegistryRefresh {

    static final Duration LIMIT = Duration.ofMinutes(1);

    private final LightRegistry lightRegistry;

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return ScheduledPass.bounded("light registry refresh", LIMIT, lightRegistry::refresh);
    }
}
