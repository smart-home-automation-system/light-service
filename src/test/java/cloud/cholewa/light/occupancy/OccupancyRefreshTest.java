package cloud.cholewa.light.occupancy;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//HAS-302: a refresh of the sensors that hangs ends at its bound, below the five minutes between refreshes
class OccupancyRefreshTest {

    private final OccupancyRegistry registry = mock(OccupancyRegistry.class);
    private final OccupancyReads reads = mock(OccupancyReads.class);
    private final OccupancyRefresh scheduled = new OccupancyRefresh(registry, reads);

    @Test
    void should_end_a_refresh_that_never_completes_at_its_bound() {
        when(registry.refresh()).thenReturn(Mono.never());
        when(reads.read()).thenReturn(Mono.empty());

        StepVerifier.withVirtualTime(scheduled::refresh)
            .expectSubscription()
            .expectNoEvent(OccupancyRefresh.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}
