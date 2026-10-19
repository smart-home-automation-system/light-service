package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.light.config.EmptyHouseProperties;
import cloud.cholewa.light.database.model.RuleRunEntity;
import cloud.cholewa.light.database.repository.RuleRunRepository;
import cloud.cholewa.light.model.CommandSource;
import cloud.cholewa.light.model.LightStatus;
import cloud.cholewa.light.model.Turn;
import cloud.cholewa.light.model.emptyhouse.PhaseName;
import cloud.cholewa.light.model.emptyhouse.PhasePlan;
import cloud.cholewa.light.model.emptyhouse.SimulationPlan;
import cloud.cholewa.light.model.emptyhouse.Spot;
import cloud.cholewa.light.model.emptyhouse.SpotRole;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.light.service.LightCommandService;
import cloud.cholewa.light.state.LightStateTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static cloud.cholewa.light.rule.RoomRuleFixtures.BEDROOM_CEILING;
import static cloud.cholewa.light.rule.RoomRuleFixtures.KITCHEN_CEILING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The empty house around its pure parts (HAS-263): what it reads, when it acts, what it sends with which
//source, what it logs and announces - and that nothing it meets fails a pass
class EmptyHouseEngineTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final LocalDateTime NOON = DAY.atTime(12, 0);

    private final AtomicReference<LocalDateTime> now = new AtomicReference<>(NOON);
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

    private final PresenceClient presenceClient = mock(PresenceClient.class);
    private final HouseState houseState = new HouseState();
    private final SunCalendar sunCalendar = mock(SunCalendar.class);
    private final SimulationPlanService simulationPlanService = mock(SimulationPlanService.class);
    private final LightRegistry lightRegistry = mock(LightRegistry.class);
    private final LightStateTracker lightStateTracker = mock(LightStateTracker.class);
    private final LightCommandService lightCommandService = mock(LightCommandService.class);
    private final RuleRunRepository ruleRunRepository = mock(RuleRunRepository.class);
    private final NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
    private EmptyHouseEngine engine = engine(true);

    @BeforeEach
    void setUp() {
        when(sunCalendar.of(any())).thenAnswer(invocation -> {
            final LocalDate date = invocation.getArgument(0);
            return new SunDay(date, LocalTime.of(7, 0), LocalTime.of(17, 30));
        });
        when(lightRegistry.current()).thenReturn(List.of(BEDROOM_CEILING, KITCHEN_CEILING));
        when(lightStateTracker.status(anyLong())).thenReturn(Mono.just(off()));
        when(lightCommandService.turn(anyLong(), any(), any())).thenReturn(Mono.empty());
        when(ruleRunRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(notificationPublisher.publishInfo(anyString())).thenReturn(Mono.empty());
        when(simulationPlanService.get()).thenReturn(Mono.just(plan()));
        everybodyAway();
    }

    //empty at 12:00, nothing before 12:10; at 12:10 the light left on goes off, one notification
    @Test
    void should_switch_off_the_lights_left_on_by_day_after_the_delay_and_announce_it_once() {
        when(lightStateTracker.status(BEDROOM_CEILING.id())).thenReturn(Mono.just(on("dashboard", NOON.minusHours(1))));

        tick();
        now.set(NOON.plusMinutes(9));
        tick();
        verify(lightCommandService, never()).turn(anyLong(), any(), any());

        now.set(NOON.plusMinutes(10));
        tick();
        now.set(NOON.plusMinutes(11));
        tick();

        verify(lightCommandService, times(1)).turn(BEDROOM_CEILING.id(), Turn.OFF, CommandSource.EMPTY_HOUSE);
        verify(ruleRunRepository).save(new RuleRunEntity(null, "BEDROOM", BEDROOM_CEILING.id(), "off", "empty-house", null,
            NOON.plusMinutes(10)));
        verify(notificationPublisher, times(1))
            .publishInfo("Nobody is at home since 12:00 - the lights left on are switched off: bedroom.");
    }

    //at night the simulation lights what its plan says now, and announces itself once
    @Test
    void should_run_the_presence_simulation_by_night() {
        now.set(DAY.atTime(17, 0));
        tick();
        now.set(DAY.atTime(18, 0));
        tick();
        now.set(DAY.atTime(18, 1));
        tick();

        verify(lightCommandService, times(1)).turn(KITCHEN_CEILING.id(), Turn.ON, CommandSource.EMPTY_HOUSE);
        verify(lightCommandService, never()).turn(BEDROOM_CEILING.id(), Turn.ON, CommandSource.EMPTY_HOUSE);
        verify(notificationPublisher, times(1))
            .publishInfo("Nobody is at home since 17:00 - the presence simulation runs until the sunrise at 07:00.");
    }

    //a database away at night: the pass is skipped - taken for no plan it would switch the simulation off
    @Test
    void should_skip_a_night_pass_whose_plan_cannot_be_read() {
        when(lightStateTracker.status(KITCHEN_CEILING.id())).thenReturn(Mono.just(on("empty-house", DAY.atTime(18, 0, 5))));
        when(simulationPlanService.get()).thenReturn(Mono.error(new IllegalStateException("database away")));
        now.set(DAY.atTime(17, 0));
        tick();
        now.set(DAY.atTime(18, 0));
        tick();

        verify(lightCommandService, never()).turn(anyLong(), any(), any());
        verify(notificationPublisher, never()).publishInfo(anyString());
    }

    //at the sunrise the simulation switches its own lights off - without the notification of the lights left on
    @Test
    void should_switch_the_simulation_off_at_the_sunrise_without_a_notification_of_the_day() {
        now.set(DAY.atTime(17, 0));
        tick();
        now.set(DAY.atTime(18, 0));
        tick();
        when(lightStateTracker.status(KITCHEN_CEILING.id())).thenReturn(Mono.just(on("empty-house", DAY.atTime(18, 0, 5))));
        now.set(DAY.plusDays(1).atTime(7, 0));
        tick();

        verify(lightCommandService).turn(KITCHEN_CEILING.id(), Turn.OFF, CommandSource.EMPTY_HOUSE);
        verify(notificationPublisher, times(1)).publishInfo(anyString());
    }

    @Test
    void should_do_nothing_while_somebody_is_at_home_or_nobody_can_tell() {
        when(lightStateTracker.status(BEDROOM_CEILING.id())).thenReturn(Mono.just(on("dashboard", NOON.minusHours(1))));
        when(presenceClient.residents()).thenReturn(Mono.just(List.of(new ResidentPresence("Kate", true, NOON, NOON))));
        tick();
        when(presenceClient.residents()).thenReturn(Mono.error(new IllegalStateException("presence-service away")));
        now.set(NOON.plusMinutes(30));
        tick();

        verify(lightCommandService, never()).turn(anyLong(), any(), any());
        assertThat(houseState.occupancy()).isEqualTo(HouseOccupancy.UNKNOWN);
    }

    //somebody back: it ends, the lights stay as they are; the next empty house is announced again
    @Test
    void should_end_when_somebody_comes_back_and_start_anew_the_next_time() {
        when(lightStateTracker.status(BEDROOM_CEILING.id())).thenReturn(Mono.just(on("dashboard", NOON.minusHours(1))));
        tick();
        now.set(NOON.plusMinutes(10));
        tick();

        when(presenceClient.residents()).thenReturn(Mono.just(List.of(new ResidentPresence("Kate", true, NOON, NOON.plusMinutes(11)))));
        now.set(NOON.plusMinutes(11));
        tick();
        assertThat(houseState.active(now.get())).isFalse();

        everybodyAway();
        now.set(NOON.plusMinutes(20));
        tick();
        now.set(NOON.plusMinutes(30));
        tick();

        verify(lightCommandService, times(2)).turn(BEDROOM_CEILING.id(), Turn.OFF, CommandSource.EMPTY_HOUSE);
        verify(notificationPublisher, times(2)).publishInfo(anyString());
    }

    @Test
    void should_watch_but_not_act_when_switched_off() {
        engine = engine(false);
        when(lightStateTracker.status(BEDROOM_CEILING.id())).thenReturn(Mono.just(on("dashboard", NOON.minusHours(1))));
        tick();
        now.set(NOON.plusMinutes(30));
        tick();

        verify(lightCommandService, never()).turn(anyLong(), any(), any());
        assertThat(houseState.active(now.get())).isTrue();
    }

    //a reactive @Scheduled method is subscribed again for every run: now is read inside the chain
    @Test
    void should_read_the_clock_anew_for_every_run() {
        when(lightStateTracker.status(BEDROOM_CEILING.id())).thenReturn(Mono.just(on("dashboard", NOON.minusHours(1))));
        final Mono<Void> tick = engine.tick();

        tick.as(StepVerifier::create).verifyComplete();
        now.set(NOON.plusMinutes(10));
        tick.as(StepVerifier::create).verifyComplete();

        verify(lightCommandService).turn(BEDROOM_CEILING.id(), Turn.OFF, CommandSource.EMPTY_HOUSE);
    }

    //a notification or a log that fails fails nothing
    @Test
    void should_carry_on_past_a_notification_and_a_log_that_fail() {
        when(lightStateTracker.status(BEDROOM_CEILING.id())).thenReturn(Mono.just(on("dashboard", NOON.minusHours(1))));
        when(notificationPublisher.publishInfo(anyString())).thenReturn(Mono.error(new IllegalStateException("broker away")));
        org.mockito.Mockito.doReturn(Mono.error(new IllegalStateException("database away"))).when(ruleRunRepository).save(any());
        tick();
        now.set(NOON.plusMinutes(10));

        engine.tick().as(StepVerifier::create).verifyComplete();
        verify(lightCommandService).turn(BEDROOM_CEILING.id(), Turn.OFF, CommandSource.EMPTY_HOUSE);
    }

    //the status: the house, when the service acts, and by night what the simulation lights now
    @Test
    void should_answer_the_status_of_the_empty_house() {
        now.set(DAY.atTime(18, 0));
        tick();

        engine.status().as(StepVerifier::create)
            .assertNext(status -> {
                assertThat(status.house()).isEqualTo("empty");
                assertThat(status.emptySince()).isEqualTo(DAY.atTime(18, 0));
                assertThat(status.actingFrom()).isEqualTo(DAY.atTime(18, 10));
                assertThat(status.active()).isFalse();
                assertThat(status.dusk()).isEqualTo(DAY.atTime(17, 30));
                assertThat(status.phase()).isEqualTo("evening");
                assertThat(status.lit()).containsExactly(KITCHEN_CEILING.id());
            })
            .verifyComplete();
    }

    private void tick() {
        engine.tick().as(StepVerifier::create).verifyComplete();
    }

    private void everybodyAway() {
        when(presenceClient.residents()).thenAnswer(invocation -> Mono.just(List.of(
            new ResidentPresence("Kate", false, NOON.minusHours(2), now.get()))));
    }

    private EmptyHouseEngine engine(final boolean enabled) {
        return new EmptyHouseEngine(presenceClient, houseState, new EmptyHouseProperties(enabled, Duration.ofMinutes(10)),
            sunCalendar, simulationPlanService, lightRegistry, lightStateTracker, lightCommandService, ruleRunRepository,
            notificationPublisher, clock);
    }

    //the kitchen the whole evening, no jitter: a plan that leaves nothing to chance
    private static SimulationPlan plan() {
        return new SimulationPlan(List.of(new PhasePlan(PhaseName.EVENING, true, LocalTime.of(16, 0), LocalTime.of(23, 0),
            0, 180, 180, 1, null, null, List.of(new Spot(SpotRole.STAY, "kitchen", List.of(KITCHEN_CEILING.id()))))));
    }

    private static LightStatus on(final String source, final LocalDateTime changedAt) {
        return new LightStatus(true, null, null, null, changedAt, source, true);
    }

    private static LightStatus off() {
        return new LightStatus(false, null, null, null, NOON.minusHours(5), "external", true);
    }
}
