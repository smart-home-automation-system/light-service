package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightStatus;
import cloud.cholewa.light.rule.RuleEvaluator.LightSnapshot;
import cloud.cholewa.light.rule.RuleEvaluator.RuleAction;
import cloud.cholewa.light.rule.RuleReason;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static cloud.cholewa.light.rule.RoomRuleFixtures.BEDROOM_CEILING;
import static cloud.cholewa.light.rule.RoomRuleFixtures.HALL_LEFT;
import static cloud.cholewa.light.rule.RoomRuleFixtures.KITCHEN_CEILING;
import static org.assertj.core.api.Assertions.assertThat;

//What the empty house switches (HAS-263): by day every light that is on goes off, by night the lights match
//what the simulation lights now; a light a person switched while the house was empty is left alone; one
//command until the light reports a change
class EmptyHouseEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 21, 0);
    private static final LocalDateTime EMPTY_SINCE = NOW.minusHours(3);

    @Test
    void should_switch_off_every_light_that_is_on_by_day_whoever_switched_it() {
        final List<RuleAction> actions = EmptyHouseEvaluator.decide(List.of(
            light(BEDROOM_CEILING, true, "dashboard", EMPTY_SINCE.minusMinutes(2)),
            light(KITCHEN_CEILING, false, "external", NOW.minusHours(1)),
            new LightSnapshot(HALL_LEFT, LightStatus.unknown(), null)), Set.of(), false, EMPTY_SINCE);

        assertThat(actions).containsExactly(new RuleAction(BEDROOM_CEILING, false, RuleReason.EMPTY_HOUSE, null));
    }

    //by night: on what should be lit, off what should not - the forgotten ones and the simulation's own
    @Test
    void should_match_the_lights_to_the_simulation_by_night() {
        final List<RuleAction> actions = EmptyHouseEvaluator.decide(List.of(
            light(BEDROOM_CEILING, true, "empty-house", NOW.minusMinutes(20)),
            light(KITCHEN_CEILING, false, "empty-house", NOW.minusMinutes(20)),
            new LightSnapshot(HALL_LEFT, LightStatus.unknown(), null)), Set.of(KITCHEN_CEILING.id(), HALL_LEFT.id()), true, EMPTY_SINCE);

        assertThat(actions).containsExactly(
            new RuleAction(BEDROOM_CEILING, false, RuleReason.PRESENCE_SIMULATION, null),
            new RuleAction(KITCHEN_CEILING, true, RuleReason.PRESENCE_SIMULATION, null),
            new RuleAction(HALL_LEFT, true, RuleReason.PRESENCE_SIMULATION, null));
    }

    //a cleaner switched the kitchen on, the dashboard the bedroom off, both after the house became empty: never
    //against a person - by day and by night
    @Test
    void should_leave_alone_a_light_somebody_switched_after_the_house_became_empty() {
        final List<LightSnapshot> lights = List.of(
            light(KITCHEN_CEILING, true, "external", EMPTY_SINCE.plusHours(1)),
            light(BEDROOM_CEILING, false, "dashboard", EMPTY_SINCE.plusMinutes(5)));

        assertThat(EmptyHouseEvaluator.decide(lights, Set.of(), false, EMPTY_SINCE)).isEmpty();
        assertThat(EmptyHouseEvaluator.decide(lights, Set.of(BEDROOM_CEILING.id()), true, EMPTY_SINCE)).isEmpty();
    }

    @Test
    void should_leave_a_light_that_is_as_it_should_be() {
        assertThat(EmptyHouseEvaluator.decide(List.of(light(KITCHEN_CEILING, true, "empty-house", NOW.minusMinutes(5))),
            Set.of(KITCHEN_CEILING.id()), true, EMPTY_SINCE)).isEmpty();
    }

    //commanded at 21:00 and not reported since: not again; reported a change at 21:01: free
    @Test
    void should_send_a_light_one_command_until_it_reports_a_change() {
        final LightSnapshot waiting = new LightSnapshot(BEDROOM_CEILING,
            new LightStatus(true, null, null, null, NOW.minusHours(1), "empty-house", true), NOW);
        final LightSnapshot changed = new LightSnapshot(BEDROOM_CEILING,
            new LightStatus(true, null, null, null, NOW.plusMinutes(1), "empty-house", true), NOW);

        assertThat(EmptyHouseEvaluator.decide(List.of(waiting), Set.of(), false, EMPTY_SINCE)).isEmpty();
        assertThat(EmptyHouseEvaluator.decide(List.of(changed), Set.of(), false, EMPTY_SINCE)).hasSize(1);
    }

    private static LightSnapshot light(final Light light, final boolean on, final String source, final LocalDateTime changedAt) {
        return new LightSnapshot(light, new LightStatus(on, null, null, null, changedAt, source, true), null);
    }
}
