package cloud.cholewa.light.occupancy;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//The sensors of the occupancy from the registry, then the gateways and the Presence devices read, at the
//start and every five minutes. Spring subscribes again to the same Mono for every run, which is why both
//steps defer their work. fixedDelay: the next pass waits for the last one to end. Scheduling is off under
//the test profile
@Component
@RequiredArgsConstructor
public class OccupancyRefresh {

    private final OccupancyRegistry occupancyRegistry;
    private final OccupancyReads occupancyReads;

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return occupancyRegistry.refresh().then(occupancyReads.read());
    }
}
