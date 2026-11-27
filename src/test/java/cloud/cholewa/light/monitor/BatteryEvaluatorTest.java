package cloud.cholewa.light.monitor;

import cloud.cholewa.light.monitor.BatteryEvaluator.Decision;
import cloud.cholewa.light.monitor.BatteryEvaluator.Level;
import cloud.cholewa.light.monitor.BatteryEvaluator.Warning;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//What the battery watch decides without I/O (HAS-530): one warning below the threshold, never a reminder, an info
//once it is back ten points above it, a level not known never low, a removed sensor forgotten without a word
class BatteryEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 12, 0);
    private static final int LOW_BELOW = 20;

    @Test
    void should_warn_once_of_a_battery_below_the_threshold() {
        final Level low = new Level(102, "Motion sensor", "sanctum", 19);

        assertThat(decide(List.of(low), Map.of()).warnings())
            .containsExactly(new Warning(102, "Motion sensor", "sanctum", 19, NOW));
        //warned already: no reminder, however low it goes
        final Warning warned = new Warning(102, "Motion sensor", "sanctum", 19, NOW.minusDays(3));
        assertThat(decide(List.of(new Level(102, "Motion sensor", "sanctum", 5)), Map.of(102L, warned)).nothing()).isTrue();
    }

    //the threshold itself is not low
    @Test
    void should_not_warn_of_a_battery_at_the_threshold() {
        assertThat(decide(List.of(new Level(102, "Motion sensor", "sanctum", 20)), Map.of()).nothing()).isTrue();
    }

    //a replaced battery: one info, with the level now - only ten points above the threshold, so a level wavering at it
    //is neither warned about nor told back every swing
    @Test
    void should_tell_a_battery_back_only_well_above_the_threshold() {
        final Warning warned = new Warning(102, "Motion sensor", "sanctum", 19, NOW.minusDays(3));
        final Level wavering = new Level(102, "Motion sensor", "sanctum", 29);
        final Level replaced = new Level(102, "Motion sensor", "sanctum", 30);

        assertThat(decide(List.of(new Level(102, "Motion sensor", "sanctum", 20)), Map.of(102L, warned)).nothing()).isTrue();
        assertThat(decide(List.of(wavering), Map.of(102L, warned)).nothing()).isTrue();
        assertThat(decide(List.of(replaced), Map.of(102L, warned)).returns()).containsExactly(replaced);
    }

    //after a start, or from a gateway that does not say: neither low nor back
    @Test
    void should_decide_nothing_of_a_level_not_known() {
        final Warning warned = new Warning(102, "Motion sensor", "sanctum", 12, NOW.minusDays(3));

        assertThat(decide(List.of(new Level(94, "Motion sensor", "office", null)), Map.of()).nothing()).isTrue();
        assertThat(decide(List.of(new Level(102, "Motion sensor", "sanctum", null)), Map.of(102L, warned)).nothing()).isTrue();
    }

    @Test
    void should_forget_the_warning_of_a_sensor_no_longer_watched() {
        final Warning gone = new Warning(999, "Gone", "loft", 10, NOW.minusDays(1));

        assertThat(decide(List.of(), Map.of(999L, gone)).forgotten()).containsExactly(gone);
    }

    private static Decision decide(final List<Level> levels, final Map<Long, Warning> open) {
        return BatteryEvaluator.decide(levels, open, NOW, LOW_BELOW);
    }
}
