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
import cloud.cholewa.light.model.RoomOccupancy;
import cloud.cholewa.light.model.rule.RoomRule;
import cloud.cholewa.light.occupancy.OccupancyRegistry;
import cloud.cholewa.light.occupancy.OccupancySensor;
import cloud.cholewa.light.occupancy.OccupancyTracker;
import cloud.cholewa.light.occupancy.SensorBinding;
import cloud.cholewa.light.occupancy.SensorKind;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.light.rule.RuleBook;
import cloud.cholewa.light.rule.RuleEngine;
import cloud.cholewa.light.state.Gen1Action;
import cloud.cholewa.light.state.LightStateTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private final OccupancyRegistry occupancyRegistry = mock(OccupancyRegistry.class);
    private final OccupancyTracker occupancyTracker = mock(OccupancyTracker.class);
    private final RuleEngine ruleEngine = mock(RuleEngine.class);
    private final RuleBook ruleBook = mock(RuleBook.class);
    private final LightQueryService service = new LightQueryService(registry, tracker, occupancyRegistry, occupancyTracker,
        ruleEngine, ruleBook, Clock.fixed(EVENING.plusMinutes(10).atZone(ZoneId.of("Europe/Warsaw")).toInstant(), ZoneId.of("Europe/Warsaw")));

    @BeforeEach
    void setUp() {
        when(tracker.status(anyLong())).thenReturn(Mono.just(LightStatus.unknown()));
        when(tracker.status(21)).thenReturn(Mono.just(new LightStatus(false, null, null, null, EVENING, "external", null)));
        when(tracker.status(23)).thenReturn(Mono.just(new LightStatus(true, 40, null, null, EVENING, "dashboard", true)));
        when(tracker.observe(any(), any())).thenReturn(Mono.empty());
        when(occupancyRegistry.loaded()).thenReturn(true);
        when(ruleEngine.motion(any())).thenReturn(Mono.empty());
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

    //the hold of the rule of the room after a change that was not the rules' (HAS-262), only while it lasts
    @Test
    void should_answer_the_manual_hold_of_a_light_while_it_lasts() {
        when(registry.find(21)).thenReturn(Mono.just(CEILING));
        when(ruleBook.rule(RoomName.KITCHEN)).thenReturn(kitchenRule(60), kitchenRule(5));

        service.one(21).as(StepVerifier::create)
            .assertNext(view -> assertThat(view.heldUntil()).isEqualTo(EVENING.plusMinutes(60)))
            .verifyComplete();
        service.one(21).as(StepVerifier::create)
            .assertNext(view -> assertThat(view.heldUntil()).isNull())
            .verifyComplete();
    }

    @Test
    void should_answer_no_hold_in_a_room_without_a_rule() {
        when(registry.find(23)).thenReturn(Mono.just(BAR));

        service.one(23).as(StepVerifier::create)
            .assertNext(view -> assertThat(view.heldUntil()).isNull())
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

    //HAS-260: the room read carries the occupancy of the room
    @Test
    void should_answer_a_room_with_its_occupancy() {
        final RoomOccupancy occupancy = new RoomOccupancy("kitchen", true, EVENING, null, null, null, List.of());
        when(registry.inRoom(RoomName.KITCHEN)).thenReturn(Mono.just(List.of(CEILING)));
        when(occupancyTracker.room(RoomName.KITCHEN)).thenReturn(occupancy);

        service.inRoom(RoomName.KITCHEN).as(StepVerifier::create)
            .assertNext(room -> assertThat(room.occupancy()).isSameAs(occupancy))
            .verifyComplete();
    }

    @Test
    void should_answer_the_occupancy_of_a_room_without_lights() {
        final RoomOccupancy occupancy = new RoomOccupancy("loft", null, null, null, null, null, List.of());
        when(occupancyTracker.room(RoomName.LOFT)).thenReturn(occupancy);

        service.occupancy(RoomName.LOFT).as(StepVerifier::create).expectNext(occupancy).verifyComplete();
    }

    //the motion_on / motion_off of a Shelly Motion, with the lux its URL carries
    @Test
    void should_take_a_gen1_motion_with_its_lux() {
        final OccupancySensor sanctum = gen1Motion(94);
        when(occupancyRegistry.sensors()).thenReturn(List.of(sanctum));

        service.gen1Motion(94, true, 12.0).as(StepVerifier::create).verifyComplete();

        verify(occupancyTracker).motion(sanctum, true);
        verify(occupancyTracker).illumination(sanctum, null, 12.0);
    }

    //the lux first, then the motion, which goes to the rules of the room (HAS-262); its end does not
    @Test
    void should_hand_a_gen1_motion_to_the_rules_after_its_lux() {
        final OccupancySensor sanctum = gen1Motion(94);
        when(occupancyRegistry.sensors()).thenReturn(List.of(sanctum));
        when(occupancyTracker.motion(sanctum, true)).thenReturn(true);

        service.gen1Motion(94, true, 12.0).as(StepVerifier::create).verifyComplete();
        service.gen1Motion(94, false, null).as(StepVerifier::create).verifyComplete();

        final InOrder order = inOrder(occupancyTracker, ruleEngine);
        order.verify(occupancyTracker).illumination(sanctum, null, 12.0);
        order.verify(occupancyTracker).motion(sanctum, true);
        order.verify(ruleEngine).motion(sanctum.room());
        verify(ruleEngine).motion(any());
    }

    @Test
    void should_take_the_end_of_a_gen1_motion_without_a_lux() {
        final OccupancySensor sanctum = gen1Motion(94);
        when(occupancyRegistry.sensors()).thenReturn(List.of(sanctum));

        service.gen1Motion(94, false, null).as(StepVerifier::create).verifyComplete();

        verify(occupancyTracker).motion(sanctum, false);
        verify(occupancyTracker, never()).illumination(any(), any(), any());
    }

    //a device configured with the wrong id, or one that is no Shelly Motion of the registry
    @Test
    void should_refuse_a_gen1_motion_of_a_device_that_is_no_gen1_motion_sensor() {
        when(occupancyRegistry.sensors()).thenReturn(List.of(new OccupancySensor(51, "Stairs motion sensor", RoomName.STAIRS,
            SensorKind.MOTION, new SensorBinding.EatonMotion(EatonGatewayType.LIGHTS, 92))));

        service.gen1Motion(51, true, null).as(StepVerifier::create)
            .verifyErrorSatisfies(error -> assertThat(error).isInstanceOf(LightException.class)
                .extracting(e -> ((LightException) e).getError()).isEqualTo(LightError.NOT_FOUND_LIGHT));
        verifyNoInteractions(occupancyTracker);
    }

    //right after a start the registry is read first: a real sensor must not get a 404
    @Test
    void should_read_the_registry_first_when_a_gen1_motion_comes_before_it() {
        final OccupancySensor sanctum = gen1Motion(94);
        when(occupancyRegistry.loaded()).thenReturn(false, true);
        when(occupancyRegistry.refresh()).thenReturn(Mono.empty());
        when(occupancyRegistry.sensors()).thenReturn(List.of(sanctum));

        service.gen1Motion(94, true, null).as(StepVerifier::create).verifyComplete();

        verify(occupancyRegistry).refresh();
        verify(occupancyTracker).motion(sanctum, true);
    }

    @Test
    void should_answer_a_gen1_motion_with_registry_unavailable_while_the_registry_is_away() {
        when(occupancyRegistry.loaded()).thenReturn(false);
        when(occupancyRegistry.refresh()).thenReturn(Mono.empty());

        service.gen1Motion(94, true, null).as(StepVerifier::create)
            .verifyErrorSatisfies(error -> assertThat(((LightException) error).getError()).isEqualTo(LightError.REGISTRY_UNAVAILABLE));
        verifyNoInteractions(occupancyTracker);
    }

    private static RoomRule kitchenRule(final int manualHoldMinutes) {
        return new RoomRule("kitchen", true, 15, false, null, null, List.of(), manualHoldMinutes, true, EVENING);
    }

    private static OccupancySensor gen1Motion(final long id) {
        return new OccupancySensor(id, "Motion", RoomName.SANCTUM, SensorKind.MOTION, new SensorBinding.Gen1Motion("10.0.0.9"));
    }

    private static Light light(final long id, final RoomName room, final LightKind kind, final LightBinding binding) {
        return new Light(id, "Light " + id, room,
            binding instanceof LightBinding.Eaton ? SmartVendor.EATON : SmartVendor.SHELLY, binding, kind);
    }
}
