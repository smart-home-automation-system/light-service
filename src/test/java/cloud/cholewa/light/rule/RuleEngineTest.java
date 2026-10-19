package cloud.cholewa.light.rule;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.database.model.RuleRunEntity;
import cloud.cholewa.light.database.repository.RuleRunRepository;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Color;
import cloud.cholewa.light.model.CommandSource;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightStatus;
import cloud.cholewa.light.model.RoomOccupancy;
import cloud.cholewa.light.model.Turn;
import cloud.cholewa.light.model.rule.RoomRule;
import cloud.cholewa.light.occupancy.OccupancyTracker;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.light.service.LightCommandService;
import cloud.cholewa.light.state.LightStateTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static cloud.cholewa.light.rule.RoomRuleFixtures.BEDROOM_CEILING;
import static cloud.cholewa.light.rule.RoomRuleFixtures.HALL_LEFT;
import static cloud.cholewa.light.rule.RoomRuleFixtures.KITCHEN_CEILING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The engine around RuleEvaluator (HAS-262): what it reads, what it sends and with which source, what it
//logs, and that nothing it meets fails a pass or a motion
class RuleEngineTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime EVENING = LocalDateTime.of(2026, 10, 19, 20, 0);
    private static final Light BEDROOM_RELAY = new Light(33, "Wall", RoomName.BEDROOM, KITCHEN_CEILING.vendor(),
        KITCHEN_CEILING.binding(), KITCHEN_CEILING.kind());
    private static final Light HALL_DIMMER = new Light(122, "Dimmer", RoomName.HALL_UP, BEDROOM_CEILING.vendor(),
        BEDROOM_CEILING.binding(), BEDROOM_CEILING.kind());
    private static final Light HALL_RELAY = new Light(123, "Relay", RoomName.HALL_UP, KITCHEN_CEILING.vendor(),
        KITCHEN_CEILING.binding(), KITCHEN_CEILING.kind());

    private final AtomicReference<LocalDateTime> now = new AtomicReference<>(EVENING);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return HOUSE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get().atZone(HOUSE).toInstant();
        }
    };

    private final RoomRuleService roomRuleService = mock(RoomRuleService.class);
    private final RuleBook ruleBook = new RuleBook();
    private final LightRegistry lightRegistry = mock(LightRegistry.class);
    private final LightStateTracker lightStateTracker = mock(LightStateTracker.class);
    private final OccupancyTracker occupancyTracker = mock(OccupancyTracker.class);
    private final LightCommandService lightCommandService = mock(LightCommandService.class);
    private final RuleRunRepository ruleRunRepository = mock(RuleRunRepository.class);
    private final RuleEngine engine = new RuleEngine(roomRuleService, ruleBook, lightRegistry, lightStateTracker,
        occupancyTracker, lightCommandService, ruleRunRepository, clock);

    @BeforeEach
    void setUp() {
        engine.settle = Duration.ZERO;
        when(lightRegistry.current()).thenReturn(List.of(BEDROOM_CEILING, BEDROOM_RELAY, HALL_LEFT, HALL_DIMMER, HALL_RELAY));
        when(lightStateTracker.status(anyLong())).thenReturn(Mono.just(off()));
        when(lightCommandService.turn(anyLong(), any(), any())).thenReturn(Mono.empty());
        when(lightCommandService.brightness(anyLong(), anyInt(), any())).thenReturn(Mono.empty());
        when(lightCommandService.color(anyLong(), any(), any())).thenReturn(Mono.empty());
        when(ruleRunRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(roomRuleService.byRoom()).thenReturn(Mono.just(Map.of(RoomName.BEDROOM, bedroomRule())));
        when(occupancyTracker.room(RoomName.BEDROOM)).thenReturn(emptyBedroom(EVENING.minusHours(1)));
        when(occupancyTracker.room(RoomName.HALL_UP)).thenReturn(hallMotion(EVENING));
    }

    //the rules read again, the lights that are on switched off by the rule, each command logged
    @Test
    void should_switch_off_the_lights_of_an_empty_room_and_log_it() {
        when(lightStateTracker.status(BEDROOM_CEILING.id())).thenReturn(Mono.just(on("external", EVENING.minusHours(2))));

        engine.tick().as(StepVerifier::create).verifyComplete();

        assertThat(ruleBook.rule(RoomName.BEDROOM)).isEqualTo(bedroomRule());
        verify(lightCommandService).turn(BEDROOM_CEILING.id(), Turn.OFF, CommandSource.RULE);
        verify(lightCommandService, never()).turn(BEDROOM_RELAY.id(), Turn.OFF, CommandSource.RULE);
        verify(ruleRunRepository).save(new RuleRunEntity(null, "BEDROOM", BEDROOM_CEILING.id(), "off", "auto-off", null, EVENING));
    }

    //a reactive @Scheduled method is subscribed again for every run: now is read inside the chain
    @Test
    void should_read_the_clock_and_the_rules_anew_for_every_run() {
        when(lightStateTracker.status(BEDROOM_CEILING.id())).thenReturn(Mono.just(on("external", EVENING.minusHours(2))));
        when(occupancyTracker.room(RoomName.BEDROOM)).thenReturn(emptyBedroom(EVENING.minusMinutes(10)));
        final Mono<Void> tick = engine.tick();

        tick.as(StepVerifier::create).verifyComplete();
        verify(lightCommandService, never()).turn(anyLong(), any(), any());

        now.set(EVENING.plusMinutes(5));
        tick.as(StepVerifier::create).verifyComplete();
        verify(lightCommandService).turn(BEDROOM_CEILING.id(), Turn.OFF, CommandSource.RULE);
        verify(roomRuleService, times(2)).byRoom();
    }

    //the command failed: logged with the code, and not sent again until the light reports a change
    @Test
    void should_log_a_command_that_failed_and_not_repeat_it() {
        when(lightStateTracker.status(BEDROOM_CEILING.id())).thenReturn(Mono.just(on("external", EVENING.minusHours(2))));
        when(lightCommandService.turn(BEDROOM_CEILING.id(), Turn.OFF, CommandSource.RULE))
            .thenReturn(Mono.error(LightException.deviceFailed(BEDROOM_CEILING, new IllegalStateException("timeout"))));

        engine.tick().as(StepVerifier::create).verifyComplete();
        now.set(EVENING.plusMinutes(1));
        engine.tick().as(StepVerifier::create).verifyComplete();

        verify(lightCommandService).turn(BEDROOM_CEILING.id(), Turn.OFF, CommandSource.RULE);
        verify(ruleRunRepository).save(new RuleRunEntity(null, "BEDROOM", BEDROOM_CEILING.id(), "off", "auto-off",
            "DEVICE_FAILED", EVENING));
    }

    //cut off by its timeout with a command in flight: the light it never got to is commanded by the next
    //pass, the one in flight is not commanded again
    @Test
    void should_leave_the_lights_a_cut_off_pass_never_got_to_free_for_the_next_one() {
        when(lightStateTracker.status(BEDROOM_CEILING.id())).thenReturn(Mono.just(on("external", EVENING.minusHours(2))));
        when(lightStateTracker.status(BEDROOM_RELAY.id())).thenReturn(Mono.just(on("external", EVENING.minusHours(2))));
        when(lightCommandService.turn(BEDROOM_CEILING.id(), Turn.OFF, CommandSource.RULE)).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(engine::tick)
            .thenAwait(RuleEngine.PASS_TIMEOUT.plusSeconds(1))
            .verifyComplete();
        verify(lightCommandService, never()).turn(BEDROOM_RELAY.id(), Turn.OFF, CommandSource.RULE);

        now.set(EVENING.plusMinutes(1));
        engine.tick().as(StepVerifier::create).verifyComplete();

        verify(lightCommandService).turn(BEDROOM_RELAY.id(), Turn.OFF, CommandSource.RULE);
        verify(lightCommandService).turn(BEDROOM_CEILING.id(), Turn.OFF, CommandSource.RULE);
    }

    //the event that brought the motion is done at once, the commands go on without it
    @Test
    void should_complete_a_motion_before_its_commands_do() {
        ruleBook.replace(Map.of(RoomName.HALL_UP, hallRule()));
        when(lightCommandService.brightness(HALL_LEFT.id(), 80, CommandSource.RULE)).thenReturn(Mono.never());

        engine.motion(RoomName.HALL_UP).as(StepVerifier::create).verifyComplete();

        verify(lightCommandService).brightness(HALL_LEFT.id(), 80, CommandSource.RULE);
    }

    @Test
    void should_keep_the_last_rules_when_they_cannot_be_read() {
        engine.tick().as(StepVerifier::create).verifyComplete();
        when(roomRuleService.byRoom()).thenReturn(Mono.error(new IllegalStateException("database away")));

        engine.tick().as(StepVerifier::create).verifyComplete();

        assertThat(ruleBook.rule(RoomName.BEDROOM)).isEqualTo(bedroomRule());
    }

    //one room that fails stops none after it; a log that cannot be written fails nothing
    @Test
    void should_carry_on_past_a_room_that_fails_and_a_log_that_cannot_be_written() {
        when(roomRuleService.byRoom()).thenReturn(Mono.just(Map.of(RoomName.BEDROOM, bedroomRule(), RoomName.HALL_UP, hallRule())));
        when(occupancyTracker.room(RoomName.HALL_UP)).thenThrow(new IllegalStateException("broken"));
        when(lightStateTracker.status(BEDROOM_CEILING.id())).thenReturn(Mono.just(on("external", EVENING.minusHours(2))));
        doReturn(Mono.error(new IllegalStateException("database away"))).when(ruleRunRepository).save(any());

        engine.tick().as(StepVerifier::create).verifyComplete();

        verify(lightCommandService).turn(BEDROOM_CEILING.id(), Turn.OFF, CommandSource.RULE);
    }

    //the scheme of 16:00-22:00 (80, no colour): an rgbw light and a dimmer get the brightness, a relay is on
    @Test
    void should_light_each_light_with_what_it_can_take_at_a_motion() {
        ruleBook.replace(Map.of(RoomName.HALL_UP, hallRule()));

        engine.motion(RoomName.HALL_UP).as(StepVerifier::create).verifyComplete();

        verify(lightCommandService).brightness(HALL_LEFT.id(), 80, CommandSource.RULE);
        verify(lightCommandService).brightness(HALL_DIMMER.id(), 80, CommandSource.RULE);
        verify(lightCommandService).turn(HALL_RELAY.id(), Turn.ON, CommandSource.RULE);
        final ArgumentCaptor<RuleRunEntity> runs = ArgumentCaptor.forClass(RuleRunEntity.class);
        verify(ruleRunRepository, times(3)).save(runs.capture());
        assertThat(runs.getAllValues()).allSatisfy(run -> {
            assertThat(run.action()).isEqualTo("on");
            assertThat(run.reason()).isEqualTo("motion-on");
            assertThat(run.room()).isEqualTo("HALL_UP");
        });
    }

    //the night scheme carries a colour: the rgbw light takes it whole, the dimmer its brightness
    @Test
    void should_give_an_rgbw_light_the_colour_of_the_period() {
        now.set(EVENING.plusHours(3));
        when(occupancyTracker.room(RoomName.HALL_UP)).thenReturn(hallMotion(now.get()));
        ruleBook.replace(Map.of(RoomName.HALL_UP, hallRule()));

        engine.motion(RoomName.HALL_UP).as(StepVerifier::create).verifyComplete();

        verify(lightCommandService).color(HALL_LEFT.id(), new Color(List.of(255, 120, 0), null, 10), CommandSource.RULE);
        verify(lightCommandService).brightness(HALL_DIMMER.id(), 10, CommandSource.RULE);
    }

    //two motions before the lights report: one command each
    @Test
    void should_send_one_command_for_two_motions_before_the_light_reports() {
        ruleBook.replace(Map.of(RoomName.HALL_UP, hallRule()));

        engine.motion(RoomName.HALL_UP).as(StepVerifier::create).verifyComplete();
        engine.motion(RoomName.HALL_UP).as(StepVerifier::create).verifyComplete();

        verify(lightCommandService, times(1)).turn(HALL_RELAY.id(), Turn.ON, CommandSource.RULE);
    }

    @Test
    void should_do_nothing_at_a_motion_in_a_room_without_a_rule() {
        engine.motion(RoomName.HALL_UP).as(StepVerifier::create).verifyComplete();

        verify(lightStateTracker, never()).status(anyLong());
    }

    @Test
    void should_never_fail_a_motion() {
        ruleBook.replace(Map.of(RoomName.HALL_UP, hallRule()));
        when(occupancyTracker.room(RoomName.HALL_UP)).thenThrow(new IllegalStateException("broken"));

        engine.motion(RoomName.HALL_UP).as(StepVerifier::create).verifyComplete();
    }

    private static RoomRule bedroomRule() {
        final var body = RoomRuleFixtures.bedroomAutoOff();
        return new RoomRule("bedroom", body.autoOffEnabled(), body.autoOffDelayMinutes(), body.motionOnEnabled(),
            body.onlyBelowLux(), body.motionOffAfterMinutes(), body.periods(), body.manualHoldMinutes(), true, EVENING);
    }

    private static RoomRule hallRule() {
        final var body = RoomRuleFixtures.hallMotion();
        return new RoomRule("hall up", body.autoOffEnabled(), body.autoOffDelayMinutes(), body.motionOnEnabled(),
            body.onlyBelowLux(), body.motionOffAfterMinutes(), body.periods(), body.manualHoldMinutes(), true, EVENING);
    }

    private static RoomOccupancy emptyBedroom(final LocalDateTime since) {
        return new RoomOccupancy("bedroom", false, since, null, null, null,
            List.of(new RoomOccupancy.SensorView(91, "Presence", "presence sensor", since, true)));
    }

    private static RoomOccupancy hallMotion(final LocalDateTime lastMotionAt) {
        return new RoomOccupancy("hall up", null, null, lastMotionAt, null, 5.0,
            List.of(new RoomOccupancy.SensorView(95, "Motion", "motion sensor", lastMotionAt, true)));
    }

    private static LightStatus on(final String source, final LocalDateTime changedAt) {
        return new LightStatus(true, null, null, null, changedAt, source, true);
    }

    private static LightStatus off() {
        return new LightStatus(false, null, null, null, EVENING.minusHours(5), "external", true);
    }
}
