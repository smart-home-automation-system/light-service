package cloud.cholewa.light.monitor;

import cloud.cholewa.light.config.MonitorProperties;
import cloud.cholewa.light.monitor.AvailabilityEvaluator.Alert;
import cloud.cholewa.light.monitor.AvailabilityEvaluator.Decision;
import cloud.cholewa.light.monitor.AvailabilityEvaluator.Kind;
import cloud.cholewa.light.monitor.AvailabilityEvaluator.Watched;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//What the availability monitor decides without I/O (HAS-264): out after the limit of its kind, one alert,
//reminders every hour, an info on return, a removed device forgotten without a word
class AvailabilityEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);
    private static final MonitorProperties PROPERTIES = new MonitorProperties(Duration.ofMinutes(5), Duration.ofHours(1),
        Duration.ofMinutes(30), Duration.ofHours(24));

    @Test
    void should_alert_a_light_unreachable_for_five_minutes() {
        final Watched light = new Watched(110, "Main", "tobi", Kind.LIGHT, NOW.minusMinutes(5));

        assertThat(decide(List.of(new Watched(110, "Main", "tobi", Kind.LIGHT, NOW.minusMinutes(4))), Map.of()).nothing()).isTrue();
        assertThat(decide(List.of(light), Map.of()).alerts())
            .containsExactly(new Alert(110, Kind.LIGHT, "Main", "tobi", NOW.minusMinutes(5), NOW));
    }

    //a light that is reachable, or whose reachability is not known, is not out
    @Test
    void should_not_judge_a_light_that_is_reachable_or_not_known() {
        assertThat(decide(List.of(new Watched(110, "Main", "tobi", Kind.LIGHT, null)), Map.of()).nothing()).isTrue();
    }

    //a presence sensor after 30 minutes of silence, a battery sensor after a day
    @Test
    void should_alert_a_sensor_after_the_silence_of_its_kind() {
        final Watched presence = new Watched(92, "Presence", "livia", Kind.PRESENCE_SENSOR, NOW.minusMinutes(30));
        final Watched quietMotion = new Watched(95, "Motion", "hall up", Kind.BATTERY_SENSOR, NOW.minusHours(23));
        final Watched silentMotion = new Watched(96, "Motion", "tobi", Kind.BATTERY_SENSOR, NOW.minusHours(24));

        assertThat(decide(List.of(presence, quietMotion, silentMotion), Map.of()).alerts())
            .extracting(Alert::id).containsExactly(92L, 96L);
    }

    @Test
    void should_remind_an_outage_every_hour() {
        final Watched light = new Watched(110, "Main", "tobi", Kind.LIGHT, NOW.minusHours(2));
        final Alert recent = new Alert(110, Kind.LIGHT, "Main", "tobi", NOW.minusHours(2), NOW.minusMinutes(59));
        final Alert hourAgo = new Alert(110, Kind.LIGHT, "Main", "tobi", NOW.minusHours(2), NOW.minusHours(1));

        assertThat(decide(List.of(light), Map.of(110L, recent)).nothing()).isTrue();
        assertThat(decide(List.of(light), Map.of(110L, hourAgo)).reminders()).containsExactly(hourAgo);
    }

    @Test
    void should_announce_a_return() {
        final Alert alert = new Alert(110, Kind.LIGHT, "Main", "tobi", NOW.minusHours(2), NOW.minusMinutes(10));

        assertThat(decide(List.of(new Watched(110, "Main", "tobi", Kind.LIGHT, null)), Map.of(110L, alert)).returns())
            .containsExactly(alert);
    }

    //removed from the registry while out: nothing to say, the alert is closed
    @Test
    void should_forget_the_alert_of_a_device_no_longer_watched() {
        final Alert alert = new Alert(999, Kind.LIGHT, "Gone", "loft", NOW.minusHours(2), NOW.minusMinutes(10));
        final Decision decision = decide(List.of(), Map.of(999L, alert));

        assertThat(decision.forgotten()).containsExactly(alert);
        assertThat(decision.returns()).isEmpty();
    }

    private static Decision decide(final List<Watched> watched, final Map<Long, Alert> open) {
        return AvailabilityEvaluator.decide(watched, open, NOW, PROPERTIES);
    }
}
