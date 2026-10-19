package cloud.cholewa.light.rabbit;

import cloud.cholewa.light.occupancy.OccupancyEventRouter;
import cloud.cholewa.light.state.LightEventRouter;
import org.junit.jupiter.api.BeforeEach;
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

    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    private final LightEventRouter router = mock(LightEventRouter.class);
    private final OccupancyEventRouter occupancyRouter = mock(OccupancyEventRouter.class);
    private final DeviceEventConsumer consumer = new DeviceEventConsumer(router, occupancyRouter);

    @BeforeEach
    void setUp() {
        when(router.route(anyString(), any())).thenReturn(Mono.empty());
        when(occupancyRouter.route(anyString(), any())).thenReturn(Mono.empty());
    }

    //the lights and the sensors of the occupancy (HAS-260) each pick their own events
    @ParameterizedTest
    @ValueSource(strings = {"shellydimmerg3-0892724f4e30.status.light:0", "eaton.lights.35", "printer.online"})
    void should_hand_every_event_to_both_routers(final String routingKey) {
        consumer.consume(message(routingKey)).as(StepVerifier::create).verifyComplete();

        verify(router).route(routingKey, BODY);
        verify(occupancyRouter).route(routingKey, BODY);
    }

    //an event that errored would be redelivered at once - a tight loop for one that fails the same way
    @Test
    void should_acknowledge_an_event_a_router_failed_on() {
        when(router.route(anyString(), any())).thenReturn(Mono.error(new IllegalStateException("not json")));
        when(occupancyRouter.route(anyString(), any())).thenReturn(Mono.error(new IllegalStateException("not json")));

        consumer.consume(message("eaton.lights.35")).as(StepVerifier::create).verifyComplete();
    }

    //a PIR of the AMX system is a point of the gateway lights too: the lights cannot read its event,
    //the occupancy still gets it
    @Test
    void should_hand_an_event_to_the_occupancy_when_the_lights_failed_on_it() {
        when(router.route(anyString(), any())).thenReturn(Mono.error(new IllegalStateException("not a light")));

        consumer.consume(message("eaton.lights.92")).as(StepVerifier::create).verifyComplete();

        verify(occupancyRouter).route("eaton.lights.92", BODY);
    }

    private static Message message(final String routingKey) {
        final MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(routingKey);
        return new Message(BODY, properties);
    }
}
