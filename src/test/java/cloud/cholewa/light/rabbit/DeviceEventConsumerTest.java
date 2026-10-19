package cloud.cholewa.light.rabbit;

import cloud.cholewa.light.state.LightEventRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceEventConsumerTest {

    private final LightEventRouter router = mock(LightEventRouter.class);
    private final DeviceEventConsumer consumer = new DeviceEventConsumer(router);

    @ParameterizedTest
    @ValueSource(strings = {"shellydimmerg3-0892724f4e30.status.light:0", "eaton.lights.35", "printer.online"})
    void should_hand_every_event_to_the_router(final String routingKey) {
        when(router.route(anyString(), any())).thenReturn(Mono.empty());

        consumer.consume(message(routingKey)).as(StepVerifier::create).verifyComplete();

        verify(router).route(routingKey, "{}".getBytes(StandardCharsets.UTF_8));
    }

    //an event that errored would be redelivered at once - a tight loop for one that fails the same way
    @Test
    void should_acknowledge_an_event_the_router_failed_on() {
        when(router.route(anyString(), any())).thenReturn(Mono.error(new IllegalStateException("not json")));

        consumer.consume(message("eaton.lights.35")).as(StepVerifier::create).verifyComplete();
    }

    private static Message message(final String routingKey) {
        final MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(routingKey);
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }
}
