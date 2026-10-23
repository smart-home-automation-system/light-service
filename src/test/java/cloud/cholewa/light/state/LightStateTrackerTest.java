package cloud.cholewa.light.state;

import static org.mockito.Mockito.doReturn;
import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.database.model.LightStateEntity;
import cloud.cholewa.light.database.repository.LightStateRepository;
import cloud.cholewa.light.model.CommandSource;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.model.LightReading;
import cloud.cholewa.light.model.LightStatus;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LightStateTrackerTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime EVENING = LocalDateTime.of(2026, 10, 19, 19, 0);
    private static final Light SOFA = new Light(25, "Sofa", RoomName.LIVING_ROOM, SmartVendor.EATON,
        new LightBinding.Eaton(24, EatonGatewayType.LIGHTS), LightKind.DIMMER);
    //a Shelly light is believed as restored; an Eaton one only once it was heard (HAS-308)
    private static final Light STRIP = new Light(89, "Strip", RoomName.LIVING_ROOM, SmartVendor.SHELLY,
        new LightBinding.ShellyGen2("10.0.0.89", 0, "strip"), LightKind.RGBW);
    private static final Light UNKNOWN = new Light(99, "Nobody heard", RoomName.LIVING_ROOM, SmartVendor.SHELLY,
        new LightBinding.ShellyGen1("10.0.0.99", 0), LightKind.RELAY);
    private static final LightStatus ON_AT_40 = new LightStatus(true, 40, null, null, EVENING, "dashboard", true);

    private final LightStateRepository repository = mock(LightStateRepository.class);
    private final MovingClock clock = new MovingClock(EVENING.atZone(ZONE).toInstant());
    private LightStateTracker tracker;

    @BeforeEach
    void setUp() {
        when(repository.findLatestPerDevice()).thenReturn(Flux.empty());
        when(repository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(repository.touchLatest(anyLong(), any(), any(), any(), any())).thenReturn(Mono.just(1));
        tracker = new LightStateTracker(repository, clock);
    }

    @Test
    void should_take_the_first_report_of_a_light_as_a_change() {
        final LightStateTracker.Decision decision =
            LightStateTracker.decide(LightStatus.unknown(), new LightReading(true, 40, null, null, "button"), EVENING, null);

        assertThat(decision.changed()).isTrue();
        assertThat(decision.status()).isEqualTo(new LightStatus(true, 40, null, null, EVENING, "button", true));
    }

    @Test
    void should_take_a_switch_as_a_change_attributed_to_the_pending_command() {
        final LightStateTracker.Decision decision =
            LightStateTracker.decide(ON_AT_40, LightReading.switched(false), EVENING.plusMinutes(5), "empty-house");

        assertThat(decision.changed()).isTrue();
        assertThat(decision.status().on()).isFalse();
        assertThat(decision.status().source()).isEqualTo("empty-house");
        assertThat(decision.status().changedAt()).isEqualTo(EVENING.plusMinutes(5));
        //what the report did not say is kept: an Eaton dimmer that reports 0 keeps its level
        assertThat(decision.status().brightness()).isEqualTo(40);
    }

    //a light dimmed from a wall button stays on, but its brightness changed
    @Test
    void should_take_another_brightness_or_color_as_a_change() {
        assertThat(LightStateTracker.decide(ON_AT_40, new LightReading(true, 70, null, null, null), EVENING, null).changed())
            .isTrue();

        final LightStatus red = new LightStatus(true, 60, List.of(255, 0, 0), null, EVENING, "dashboard", true);
        final LightStateTracker.Decision green =
            LightStateTracker.decide(red, new LightReading(true, 60, List.of(0, 255, 0), null, null), EVENING, null);
        assertThat(green.changed()).isTrue();
        assertThat(green.status().source()).isEqualTo(LightStateTracker.EXTERNAL);
        assertThat(LightStateTracker.decide(red, new LightReading(true, 60, List.of(255, 0, 0), 10, null), EVENING, null)
            .changed()).isFalse();
    }

    //the same state again moves nothing but last_checked_at, and fills in what the report adds
    @Test
    void should_confirm_the_same_state_and_keep_when_and_who() {
        final LightStatus switchedOn = new LightStatus(true, null, null, null, EVENING, "external", false);

        final LightStateTracker.Decision decision = LightStateTracker.decide(
            switchedOn, new LightReading(true, 55, null, null, "init"), EVENING.plusMinutes(1), "dashboard");

        assertThat(decision.changed()).isFalse();
        assertThat(decision.status()).isEqualTo(new LightStatus(true, 55, null, null, EVENING, "external", true));
    }

    @Test
    void should_store_a_row_only_when_the_state_changes() {
        tracker.observe(SOFA, new LightReading(true, 40, null, null, null)).as(StepVerifier::create).verifyComplete();
        tracker.observe(SOFA, new LightReading(true, 40, null, null, null)).as(StepVerifier::create).verifyComplete();
        tracker.observe(SOFA, LightReading.switched(false)).as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<LightStateEntity> rows = ArgumentCaptor.forClass(LightStateEntity.class);
        verify(repository, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(LightStateEntity::switchedOn).containsExactly(true, false);
        assertThat(rows.getAllValues().get(1).brightness()).isEqualTo(40);
        verify(repository).touchLatest(eq(25L), any(), eq(40), eq(null), eq(null));
    }

    @Test
    void should_attribute_the_change_to_the_command_within_its_window_only() {
        tracker.commanded(SOFA, CommandSource.DASHBOARD);
        tracker.observe(SOFA, new LightReading(true, 40, null, null, "HTTP_in")).as(StepVerifier::create).verifyComplete();

        tracker.commanded(SOFA, CommandSource.RULE);
        clock.advance(LightStateTracker.COMMAND_WINDOW.plusSeconds(1));
        tracker.observe(SOFA, LightReading.switched(false)).as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<LightStateEntity> rows = ArgumentCaptor.forClass(LightStateEntity.class);
        verify(repository, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(LightStateEntity::source).containsExactly("dashboard", "external");
    }

    //a command is used up by the change it made, not by a confirmation of the state before it
    @Test
    void should_keep_the_command_for_its_change_over_a_confirmation() {
        tracker.observe(SOFA, LightReading.switched(false)).as(StepVerifier::create).verifyComplete();
        tracker.commanded(SOFA, CommandSource.DASHBOARD);
        tracker.observe(SOFA, LightReading.switched(false)).as(StepVerifier::create).verifyComplete();
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();

        tracker.status(SOFA).as(StepVerifier::create)
            .assertNext(status -> assertThat(status.source()).isEqualTo("dashboard"))
            .verifyComplete();
    }

    //HAS-295: the command waits for its change until the change, a newer command or the end of its window
    @Test
    void should_know_whether_a_command_still_waits_for_its_change() {
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
        final LightStateTracker.IssuedCommand first = tracker.commanded(SOFA, CommandSource.RULE);
        assertThat(tracker.awaitingChange(SOFA, first)).isTrue();

        final LightStateTracker.IssuedCommand second = tracker.commanded(SOFA, CommandSource.RULE);
        assertThat(tracker.awaitingChange(SOFA, first)).isFalse();
        assertThat(tracker.awaitingChange(SOFA, second)).isTrue();

        tracker.observe(SOFA, LightReading.switched(false)).as(StepVerifier::create).verifyComplete();
        assertThat(tracker.awaitingChange(SOFA, second)).isFalse();

        final LightStateTracker.IssuedCommand late = tracker.commanded(SOFA, CommandSource.RULE);
        clock.advance(LightStateTracker.COMMAND_WINDOW.plusSeconds(1));
        assertThat(tracker.awaitingChange(SOFA, late)).isFalse();
    }

    //the answer to the status request with the state from before: the command did not take
    @Test
    void should_take_a_confirmation_after_the_status_request_as_a_command_that_did_not_take() {
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
        final LightStateTracker.IssuedCommand off = tracker.commanded(SOFA, CommandSource.RULE);

        //a confirmation before anybody asked proves nothing
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
        assertThat(tracker.notTaken(SOFA, off)).isFalse();

        tracker.asked(off);
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.notTaken(SOFA, off)).isTrue();
        assertThat(tracker.awaitingChange(SOFA, off)).isTrue();
        assertThat(tracker.notTaken(25, CommandSource.RULE)).isTrue();
        //only the source of the command
        assertThat(tracker.notTaken(25, CommandSource.DASHBOARD)).isFalse();
    }

    //a request that could not be sent is answered by nobody: a later confirmation proves nothing
    @Test
    void should_not_take_a_confirmation_after_a_failed_status_request_as_an_answer() {
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
        final LightStateTracker.IssuedCommand off = tracker.commanded(SOFA, CommandSource.RULE);
        tracker.asked(off);
        tracker.askFailed(off);

        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.notTaken(SOFA, off)).isFalse();
    }

    //the change came with the answer: the command took, late
    @Test
    void should_take_a_change_after_the_status_request_as_a_command_that_took() {
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
        final LightStateTracker.IssuedCommand off = tracker.commanded(SOFA, CommandSource.RULE);
        tracker.asked(off);

        tracker.observe(SOFA, LightReading.switched(false)).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.notTaken(SOFA, off)).isFalse();
        assertThat(tracker.notTaken(25, CommandSource.RULE)).isFalse();
        tracker.status(SOFA).as(StepVerifier::create)
            .assertNext(status -> assertThat(status.source()).isEqualTo("rule"))
            .verifyComplete();
    }

    //a newer command replaces the one that did not take, and its window ends it
    @Test
    void should_forget_a_command_that_did_not_take_once_replaced_or_out_of_its_window() {
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
        final LightStateTracker.IssuedCommand off = tracker.commanded(SOFA, CommandSource.RULE);
        tracker.asked(off);
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();

        clock.advance(LightStateTracker.COMMAND_WINDOW.plusSeconds(1));
        assertThat(tracker.notTaken(25, CommandSource.RULE)).isFalse();

        final LightStateTracker.IssuedCommand again = tracker.commanded(SOFA, CommandSource.RULE);
        tracker.asked(again);
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
        tracker.commanded(SOFA, CommandSource.DASHBOARD);
        assertThat(tracker.notTaken(SOFA, again)).isFalse();
        assertThat(tracker.notTaken(25, CommandSource.RULE)).isFalse();
    }

    @Test
    void should_cut_the_word_of_a_device_to_the_width_of_the_column() {
        final LightStateTracker.Decision decision = LightStateTracker.decide(
            LightStatus.unknown(), new LightReading(true, null, null, null, "x".repeat(80)), EVENING, null);

        assertThat(decision.status().source()).hasSize(50);
    }

    @Test
    void should_start_from_the_latest_rows_of_the_database() {
        when(repository.findLatestPerDevice()).thenReturn(Flux.just(
            new LightStateEntity(7L, 25, true, 30, null, null, "button", EVENING, EVENING.plusHours(1)),
            new LightStateEntity(8L, 89, true, 60, "255,0,0", null, "dashboard", EVENING, EVENING)));
        tracker = new LightStateTracker(repository, clock);

        //an Eaton light restored and not heard since: on is left out, the rest stays (HAS-308)
        tracker.status(SOFA).as(StepVerifier::create)
            .assertNext(status -> assertThat(status).isEqualTo(new LightStatus(null, 30, null, null, EVENING, "button", null)))
            .verifyComplete();
        tracker.status(STRIP).as(StepVerifier::create)
            .assertNext(status -> {
                assertThat(status.on()).isTrue();
                assertThat(status.rgb()).containsExactly(255, 0, 0);
            })
            .verifyComplete();
        tracker.status(UNKNOWN).as(StepVerifier::create)
            .assertNext(status -> assertThat(status).isEqualTo(LightStatus.unknown()))
            .verifyComplete();
        verify(repository, times(1)).findLatestPerDevice();
    }

    //a database away at the start is no reason not to take the readings: they rebuild the state
    @Test
    void should_go_on_from_the_devices_when_the_restore_fails() {
        when(repository.findLatestPerDevice()).thenReturn(Flux.error(new IllegalStateException("database away")));
        tracker = new LightStateTracker(repository, clock);

        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();

        tracker.status(SOFA).as(StepVerifier::create)
            .assertNext(status -> assertThat(status.on()).isTrue())
            .verifyComplete();
    }

    //the memory knows a state the table has no row for (rows removed by hand)
    @Test
    void should_open_a_row_when_there_is_none_to_confirm() {
        when(repository.touchLatest(anyLong(), any(), any(), any(), any())).thenReturn(Mono.just(0));
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();

        verify(repository, times(2)).save(any());
    }

    @Test
    void should_know_reachability_apart_from_the_state() {
        tracker.reachability(SOFA, false, "the device left the broker").as(StepVerifier::create).verifyComplete();
        tracker.status(SOFA).as(StepVerifier::create)
            .assertNext(status -> {
                assertThat(status.reachable()).isFalse();
                assertThat(status.on()).isNull();
            })
            .verifyComplete();

        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
        tracker.status(SOFA).as(StepVerifier::create)
            .assertNext(status -> assertThat(status.reachable()).isTrue())
            .verifyComplete();
        verify(repository, never()).touchLatest(anyLong(), any(), anyInt(), anyString(), anyInt());
    }


    //HAS-308: the kitchen bar of 2026-10-22 - restored off, on in truth, the AMX away: not known until it is heard
    @Test
    void should_know_a_restored_eaton_light_once_it_was_heard() {
        when(repository.findLatestPerDevice()).thenReturn(Flux.just(
            new LightStateEntity(7L, 25, false, null, null, null, "rule", EVENING, EVENING)));
        tracker = new LightStateTracker(repository, clock);

        tracker.status(SOFA).as(StepVerifier::create)
            .assertNext(status -> {
                assertThat(status.on()).isNull();
                assertThat(status.source()).isEqualTo("rule");
                assertThat(status.changedAt()).isEqualTo(EVENING);
            })
            .verifyComplete();
        assertThat(tracker.isConfirmed(SOFA)).isFalse();

        clock.advance(Duration.ofMinutes(1));
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.isConfirmed(SOFA)).isTrue();
        tracker.status(SOFA).as(StepVerifier::create)
            .assertNext(status -> assertThat(status.on()).isTrue())
            .verifyComplete();
    }

    //a confirmation of the state already kept counts as much as a change
    @Test
    void should_know_an_eaton_light_whose_answer_confirms_what_was_restored() {
        when(repository.findLatestPerDevice()).thenReturn(Flux.just(
            new LightStateEntity(7L, 25, true, 30, null, null, "button", EVENING, EVENING)));
        tracker = new LightStateTracker(repository, clock);

        tracker.observe(SOFA, new LightReading(true, 30, null, null, null)).as(StepVerifier::create).verifyComplete();

        tracker.status(SOFA).as(StepVerifier::create)
            .assertNext(status -> assertThat(status).isEqualTo(new LightStatus(true, 30, null, null, EVENING, "button", true)))
            .verifyComplete();
        verify(repository, never()).save(any());
    }

    //FRAME_SERVER_UNAVAILABLE: every Eaton light is unknown until it is heard again; a Shelly light is not touched
    @Test
    void should_forget_every_eaton_light_while_the_amx_is_away() {
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
        tracker.observe(STRIP, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();

        clock.advance(Duration.ofMinutes(1));
        tracker.eatonUnavailable("a test");

        assertThat(tracker.isConfirmed(SOFA)).isFalse();
        assertThat(tracker.isConfirmed(STRIP)).isTrue();
        tracker.status(SOFA).as(StepVerifier::create)
            .assertNext(status -> assertThat(status.on()).isNull())
            .verifyComplete();
        tracker.status(STRIP).as(StepVerifier::create)
            .assertNext(status -> assertThat(status.on()).isTrue())
            .verifyComplete();

        clock.advance(Duration.ofMinutes(5));
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.isConfirmed(SOFA)).isTrue();
    }

    //one WARN per loss: a Shelly light heard meanwhile ends nothing, an Eaton light heard again does
    @Test
    void should_announce_a_loss_once() {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        final Logger logger = (Logger) LoggerFactory.getLogger(LightStateTracker.class);
        appender.start();
        logger.addAppender(appender);
        try {
            tracker.eatonUnavailable("a test");
            clock.advance(Duration.ofMinutes(5));
            tracker.observe(STRIP, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
            clock.advance(Duration.ofMinutes(5));
            tracker.eatonUnavailable("a test");
            clock.advance(Duration.ofMinutes(5));
            tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
            clock.advance(Duration.ofMinutes(5));
            tracker.eatonUnavailable("a test");
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .filter(event -> event.getFormattedMessage().startsWith("The AMX frame server is not available")))
            .hasSize(2);
    }

    //heard in the same instant the loss was noted is not after it
    @Test
    void should_not_count_a_light_heard_at_the_moment_of_the_loss() {
        tracker.observe(SOFA, LightReading.switched(true)).as(StepVerifier::create).verifyComplete();
        tracker.eatonUnavailable("a test");

        assertThat(tracker.isConfirmed(SOFA)).isFalse();
    }

    private static final class MovingClock extends Clock {

        private Instant now;

        private MovingClock(final Instant now) {
            this.now = now;
        }

        void advance(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    //HAS-302 (the finding of HAS-301): a write hung on a dead connection held every later write of the
    //light - the next one goes after the bound of the hung one, and the caller of the hung one is not failed
    @Test
    void should_let_the_next_write_of_a_light_go_once_a_hung_one_reaches_its_bound() {
        //doReturn: when(...) would call the answer of setUp with a null argument
        doReturn(Mono.never())
            .doAnswer(invocation -> Mono.just(invocation.getArgument(0)))
            .when(repository).save(any());

        StepVerifier.withVirtualTime(() -> Mono.when(
                tracker.observe(SOFA, LightReading.switched(true)),
                tracker.observe(SOFA, LightReading.switched(false))))
            .expectSubscription()
            .expectNoEvent(LightStateTracker.WRITE_TIMEOUT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();

        verify(repository, times(2)).save(any());
    }
}
