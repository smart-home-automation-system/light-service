package cloud.cholewa.light.registry;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//HAS-302: a read of the registry that hangs ends at its bound, below the five minutes between reads
class LightRegistryRefreshTest {

    private final LightRegistry registry = mock(LightRegistry.class);
    private final LightRegistryRefresh scheduled = new LightRegistryRefresh(registry);

    @Test
    void should_end_a_read_that_never_completes_at_its_bound() {
        when(registry.refresh()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(scheduled::refresh)
            .expectSubscription()
            .expectNoEvent(LightRegistryRefresh.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}
