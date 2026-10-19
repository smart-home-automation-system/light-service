package cloud.cholewa.light.service;

import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.error.LightError;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.model.LightReading;
import cloud.cholewa.light.model.LightStatus;
import cloud.cholewa.light.model.LightView;
import cloud.cholewa.light.model.RoomLights;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.light.state.Gen1Action;
import cloud.cholewa.light.state.LightStateTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LightQueryServiceTest {

    private static final LocalDateTime EVENING = LocalDateTime.of(2026, 10, 19, 19, 0);
    private static final Light CEILING = light(21, RoomName.KITCHEN, LightKind.RELAY,
        new LightBinding.Eaton(27, EatonGatewayType.LIGHTS));
    private static final Light BAR = light(23, RoomName.KITCHEN, LightKind.DIMMER,
        new LightBinding.Eaton(29, EatonGatewayType.LIGHTS));
    private static final Light STAIRS = light(45, RoomName.STAIRS, LightKind.DIMMER,
        new LightBinding.Eaton(21, EatonGatewayType.LIGHTS));
    private static final Light WALL_LEFT = light(90, RoomName.CINEMA, LightKind.DIMMER,
        new LightBinding.ShellyGen1("10.0.0.24", 0));

    private final LightRegistry registry = mock(LightRegistry.class);
    private final LightStateTracker tracker = mock(LightStateTracker.class);
    private final LightQueryService service = new LightQueryService(registry, tracker);

    @BeforeEach
    void setUp() {
        when(tracker.status(anyLong())).thenReturn(Mono.just(LightStatus.unknown()));
        when(tracker.status(21)).thenReturn(Mono.just(new LightStatus(false, null, null, null, EVENING, "external", null)));
        when(tracker.status(23)).thenReturn(Mono.just(new LightStatus(true, 40, null, null, EVENING, "dashboard", true)));
        when(tracker.observe(any(), any())).thenReturn(Mono.empty());
    }

    //by room in the order of the rooms of the SDK, each room's in the order of the registry
    @Test
    void should_answer_every_light_by_room() {
        when(registry.all()).thenReturn(Mono.just(List.of(WALL_LEFT, CEILING, STAIRS, BAR)));

        service.all().as(StepVerifier::create)
            .assertNext(rooms -> {
                assertThat(rooms).extracting(RoomLights::room).containsExactly("stairs", "kitchen", "cinema");
                assertThat(rooms.get(1).lights()).extracting(LightView::id).containsExactly(21L, 23L);
                assertThat(rooms.get(1).anyOn()).isTrue();
                //nothing heard of the stairs: it may be on
                assertThat(rooms.get(0).anyOn()).isNull();
            })
            .verifyComplete();
    }

    //what is not known is left out - never off or zero
    @Test
    void should_answer_one_light_with_what_is_known_of_it() {
        when(registry.find(21)).thenReturn(Mono.just(CEILING));
        when(registry.find(45)).thenReturn(Mono.just(STAIRS));

        service.one(21).as(StepVerifier::create)
            .assertNext(view -> {
                assertThat(view.on()).isFalse();
                assertThat(view.brightness()).isNull();
                assertThat(view.kind()).isEqualTo("relay");
            })
            .verifyComplete();
        service.one(45).as(StepVerifier::create)
            .assertNext(view -> {
                assertThat(view.on()).isNull();
                assertThat(view.changedAt()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_answer_a_room_where_every_light_is_off_as_none_on() {
        when(registry.inRoom(RoomName.KITCHEN)).thenReturn(Mono.just(List.of(CEILING)));

        service.inRoom(RoomName.KITCHEN).as(StepVerifier::create)
            .assertNext(room -> assertThat(room.anyOn()).isFalse())
            .verifyComplete();
    }

    @Test
    void should_take_a_gen1_action_as_a_report_of_the_light() {
        when(registry.find(90)).thenReturn(Mono.just(WALL_LEFT));

        service.gen1Action(90, Gen1Action.ON).as(StepVerifier::create).verifyComplete();

        verify(tracker).observe(WALL_LEFT, LightReading.switched(true));
    }

    //a device configured with the id of a light that is not a Gen1 one
    @Test
    void should_refuse_a_gen1_action_of_a_light_that_is_not_gen1() {
        when(registry.find(21)).thenReturn(Mono.just(CEILING));

        service.gen1Action(21, Gen1Action.OFF).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((LightException) e).getError()).isEqualTo(LightError.NOT_FOUND_LIGHT))
            .verify();

        verifyNoInteractions(tracker);
    }

    //the device does not call again: a store that failed is this service's trouble
    @Test
    void should_accept_a_gen1_action_whose_state_could_not_be_stored() {
        when(registry.find(90)).thenReturn(Mono.just(WALL_LEFT));
        when(tracker.observe(any(), any())).thenReturn(Mono.error(new IllegalStateException("database away")));

        service.gen1Action(90, Gen1Action.OFF).as(StepVerifier::create).verifyComplete();
    }

    private static Light light(final long id, final RoomName room, final LightKind kind, final LightBinding binding) {
        return new Light(id, "Light " + id, room,
            binding instanceof LightBinding.Eaton ? SmartVendor.EATON : SmartVendor.SHELLY, binding, kind);
    }
}
