package cloud.cholewa.light.emptyhouse;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//Whether the house is empty (HAS-263): an unknown house is never empty, and only somebody at home ends an
//empty house
class HouseStateTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 9, 0);
    private static final Duration AFTER = Duration.ofMinutes(10);

    @Test
    void should_take_a_house_whose_every_member_is_away_by_a_fresh_reading_for_empty() {
        assertThat(HouseState.judge(List.of(away("Kate", 1), away("Kris", 4)), NOW)).isEqualTo(HouseOccupancy.EMPTY);
    }

    @Test
    void should_take_a_house_with_one_member_at_home_for_occupied() {
        assertThat(HouseState.judge(List.of(away("Kate", 1), new ResidentPresence("Kris", true, NOW, NOW)), NOW))
            .isEqualTo(HouseOccupancy.OCCUPIED);
    }

    //a reading older than 5 minutes, a member never observed, no members at all, a call that failed
    @Test
    void should_never_take_a_house_it_cannot_tell_for_empty() {
        assertThat(HouseState.judge(List.of(away("Kate", 1), away("Kris", 6)), NOW)).isEqualTo(HouseOccupancy.UNKNOWN);
        assertThat(HouseState.judge(List.of(away("Kate", 1), new ResidentPresence("Tobi", false, null, null)), NOW))
            .isEqualTo(HouseOccupancy.UNKNOWN);
        assertThat(HouseState.judge(List.of(), NOW)).isEqualTo(HouseOccupancy.UNKNOWN);
        assertThat(HouseState.judge(null, NOW)).isEqualTo(HouseOccupancy.UNKNOWN);
    }

    @Test
    void should_act_after_the_delay_of_an_empty_house() {
        final HouseState state = new HouseState();
        state.update(HouseOccupancy.EMPTY, NOW, AFTER);

        assertThat(state.emptySince()).isEqualTo(NOW);
        assertThat(state.active(NOW.plusMinutes(9))).isFalse();
        assertThat(state.active(NOW.plusMinutes(10))).isTrue();
    }

    //a presence-service away for a while suspends acting and keeps since; somebody back ends it
    @Test
    void should_keep_an_empty_house_through_an_unknown_spell_and_end_it_when_somebody_comes_back() {
        final HouseState state = new HouseState();
        state.update(HouseOccupancy.EMPTY, NOW, AFTER);

        assertThat(state.update(HouseOccupancy.UNKNOWN, NOW.plusMinutes(20), AFTER)).isFalse();
        assertThat(state.active(NOW.plusMinutes(20))).isFalse();
        assertThat(state.emptySince()).isEqualTo(NOW);

        state.update(HouseOccupancy.EMPTY, NOW.plusMinutes(21), AFTER);
        assertThat(state.active(NOW.plusMinutes(21))).isTrue();

        assertThat(state.update(HouseOccupancy.OCCUPIED, NOW.plusMinutes(30), AFTER)).isTrue();
        assertThat(state.emptySince()).isNull();
        assertThat(state.active(NOW.plusMinutes(30))).isFalse();
    }

    @Test
    void should_be_unknown_after_a_start() {
        final HouseState state = new HouseState();

        assertThat(state.occupancy()).isEqualTo(HouseOccupancy.UNKNOWN);
        assertThat(state.active(NOW)).isFalse();
    }

    private static ResidentPresence away(final String name, final int minutesAgo) {
        return new ResidentPresence(name, false, NOW.minusHours(1), NOW.minusMinutes(minutesAgo));
    }
}
