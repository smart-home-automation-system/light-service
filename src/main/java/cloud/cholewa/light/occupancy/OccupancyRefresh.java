package cloud.cholewa.light.occupancy;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//The sensors of the occupancy from the registry, then the gateways and the Presence devices read, at the
//start and every five minutes. Spring subscribes again to the same Mono for every run, which is why both
//steps defer their work. fixedDelay: the next pass waits for the last one to end. Scheduling is off under
//the test profile.
//Bounded (HAS-302): the registry, then every gateway and Presence, each call with its own timeout - two
//minutes is a pass that hangs; what was read so far stays
@Component
@RequiredArgsConstructor
public class OccupancyRefresh {

    static final Duration LIMIT = Duration.ofMinutes(2);

    private final OccupancyRegistry occupancyRegistry;
    private final OccupancyReads occupancyReads;

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return ScheduledPass.bounded("light occupancy refresh", LIMIT,
            () -> occupancyRegistry.refresh().then(occupancyReads.read()));
    }
}
