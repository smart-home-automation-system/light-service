package cloud.cholewa.light.registry;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//The registry read at the start and every five minutes. Spring subscribes again to the same Mono
//for every run, which is why the read itself is deferred in LightRegistry.refresh(). fixedDelay:
//the next read waits for the last one to end. Scheduling is off under the test profile
@Component
@RequiredArgsConstructor
public class LightRegistryRefresh {

    private final LightRegistry lightRegistry;

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return lightRegistry.refresh();
    }
}
