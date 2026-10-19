package cloud.cholewa.light.registry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.EatonBinding;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.error.LightError;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Light;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LightRegistryTest {

    private final DeviceRegistryClient client = mock(DeviceRegistryClient.class);
    private final LightRegistry registry = new LightRegistry(client);

    @Test
    void should_know_the_lights_of_the_last_refresh() {
        when(client.getLights()).thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 9), eaton(18, RoomName.KITCHEN, 10))));

        registry.refresh().as(StepVerifier::create).verifyComplete();

        registry.find(18).as(StepVerifier::create)
            .assertNext(light -> assertThat(light.id()).isEqualTo(18))
            .verifyComplete();
        registry.inRoom(RoomName.KITCHEN).as(StepVerifier::create)
            .assertNext(lights -> assertThat(lights).extracting(Light::id).containsExactly(17L, 18L))
            .verifyComplete();
    }

    //a database-service that is away for a moment must not make every light unknown
    @Test
    void should_keep_the_last_list_when_a_refresh_fails() {
        when(client.getLights())
            .thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 9))))
            .thenReturn(Mono.error(new RegistryCallException("database-service answered 500")));

        registry.refresh().as(StepVerifier::create).verifyComplete();
        registry.refresh().as(StepVerifier::create).verifyComplete();

        registry.find(17).as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    @Test
    void should_drop_a_light_the_registry_no_longer_holds() {
        when(client.getLights())
            .thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 9))))
            .thenReturn(Mono.just(List.of()));

        registry.refresh().as(StepVerifier::create).verifyComplete();
        registry.refresh().as(StepVerifier::create).verifyComplete();

        registry.find(17).as(StepVerifier::create).expectError(LightException.class).verify();
    }

    //a command right after a start must not be refused because the pod came up before database-service
    @Test
    void should_read_the_registry_on_a_lookup_before_the_first_refresh_succeeded() {
        when(client.getLights())
            .thenReturn(Mono.error(new RegistryCallException("database-service answered 503")))
            .thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 9))));

        registry.refresh().as(StepVerifier::create).verifyComplete();
        registry.find(17).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        //known from now on: no read for the next lookup
        registry.find(17).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        verify(client, times(2)).getLights();
    }

    @Test
    void should_not_find_a_light_it_does_not_know() {
        when(client.getLights()).thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 9))));

        registry.find(99).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((LightException) e).getError()).isEqualTo(LightError.NOT_FOUND_LIGHT))
            .verify();
        registry.inRoom(RoomName.CINEMA).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((LightException) e).getError()).isEqualTo(LightError.NOT_FOUND_LIGHT))
            .verify();
    }

    //never read: "no such light" would be a lie - the caller is told the registry is away
    @Test
    void should_answer_registry_unavailable_while_the_registry_was_never_read() {
        when(client.getLights()).thenReturn(Mono.error(new RegistryCallException("database-service answered 503")));

        registry.find(17).as(StepVerifier::create)
            .expectErrorSatisfies(e -> {
                final LightException exception = (LightException) e;
                assertThat(exception.getError()).isEqualTo(LightError.REGISTRY_UNAVAILABLE);
                assertThat(exception.getHttpStatus().value()).isEqualTo(502);
            })
            .verify();
        registry.inRoom(RoomName.KITCHEN).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((LightException) e).getError()).isEqualTo(LightError.REGISTRY_UNAVAILABLE))
            .verify();
    }

    //the alerts look at the level: a failed refresh is a WARN, the service goes on with the last list
    @Test
    void should_log_a_failed_refresh_at_warn() {
        final ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        final Logger logger = (Logger) LoggerFactory.getLogger(LightRegistry.class);
        logger.addAppender(log);
        try {
            when(client.getLights()).thenReturn(Mono.error(new RegistryCallException("database-service answered 500")));

            registry.refresh().as(StepVerifier::create).verifyComplete();

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("database-service answered 500");
            });
        } finally {
            logger.detachAppender(log);
        }
    }

    //a light moved to another address keeps the count - the change is logged all the same
    @Test
    void should_log_every_change_of_the_list() {
        final ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        final Logger logger = (Logger) LoggerFactory.getLogger(LightRegistry.class);
        logger.addAppender(log);
        try {
            when(client.getLights())
                .thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 9))))
                .thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 9))))
                .thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 11))));

            registry.refresh().as(StepVerifier::create).verifyComplete();
            registry.refresh().as(StepVerifier::create).verifyComplete();
            registry.refresh().as(StepVerifier::create).verifyComplete();

            assertThat(log.list).hasSize(2).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
        } finally {
            logger.detachAppender(log);
        }
    }

    @Test
    void should_skip_a_device_it_cannot_drive_and_keep_the_rest() {
        final SmartDevice withoutPoint = eaton(19, RoomName.CINEMA, 12).eaton(new EatonBinding());
        when(client.getLights()).thenReturn(Mono.just(Arrays.asList(withoutPoint, null, eaton(17, RoomName.KITCHEN, 9))));

        registry.refresh().as(StepVerifier::create).verifyComplete();

        registry.find(19).as(StepVerifier::create).expectError(LightException.class).verify();
        registry.find(17).as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    //Spring subscribes to the Mono of the schedule again for every run: the read must happen each time
    @Test
    void should_read_the_registry_again_on_every_subscription() {
        when(client.getLights()).thenReturn(Mono.just(List.of()));
        final Mono<Void> refresh = new LightRegistryRefresh(registry).refresh();

        refresh.as(StepVerifier::create).verifyComplete();
        refresh.as(StepVerifier::create).verifyComplete();

        verify(client, times(2)).getLights();
    }

    private static SmartDevice eaton(final long id, final RoomName room, final int point) {
        return SmartDevice.builder()
            .id(id)
            .name("Light " + id)
            .room(room)
            .kind(DeviceKind.LIGHT)
            .enabled(true)
            .vendor(SmartVendor.EATON)
            .eaton(new EatonBinding().point(point))
            .lightKind(LightKind.RELAY)
            .build();
    }
}
