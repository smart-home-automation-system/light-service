package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.light.error.LightError;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.emptyhouse.PhaseName;
import cloud.cholewa.light.model.emptyhouse.PhasePlan;
import cloud.cholewa.light.model.emptyhouse.SimulationPlan;
import cloud.cholewa.light.model.emptyhouse.Spot;
import cloud.cholewa.light.model.emptyhouse.SpotRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import static cloud.cholewa.light.rule.RoomRuleFixtures.HALL_LEFT;
import static cloud.cholewa.light.rule.RoomRuleFixtures.KITCHEN_CEILING;
import static cloud.cholewa.light.rule.RoomRuleFixtures.LIGHTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//What a plan of the presence simulation has to say (HAS-263): the first broken rule, in words
class PlanRulesTest {

    private static final Spot KITCHEN = new Spot(SpotRole.STAY, "kitchen", List.of(KITCHEN_CEILING.id()));
    private static final Spot HALL = new Spot(SpotRole.PATH, "hall up", List.of(HALL_LEFT.id()));

    @Test
    void should_accept_a_plan_and_no_plan() {
        assertThatCode(() -> PlanRules.check(new SimulationPlan(List.of(evening(2, null, null, List.of(KITCHEN, HALL))))))
            .doesNotThrowAnyException();
        assertThatCode(() -> PlanRules.check(SimulationPlan.NONE)).doesNotThrowAnyException();
    }

    static Stream<Arguments> broken() {
        return Stream.of(
            Arguments.of(new SimulationPlan(null), "phases is missing - an empty list for none"),
            Arguments.of(new SimulationPlan(List.of(evening(2, null, null, List.of(KITCHEN)), evening(2, null, null, List.of(KITCHEN)))),
                "phases has each phase at most once"),
            Arguments.of(new SimulationPlan(List.of(evening(4, null, null, List.of(KITCHEN)))),
                "evening: maxSpotsAtOnce is between 1 and 3"),
            Arguments.of(new SimulationPlan(List.of(evening(2, 1, null, List.of(KITCHEN)))),
                "evening: visitsMin and visitsMax are given together or not at all"),
            Arguments.of(new SimulationPlan(List.of(evening(2, null, null, List.of(HALL)))), "evening: a phase needs a stay spot"),
            Arguments.of(new SimulationPlan(List.of(evening(2, null, null, List.of(new Spot(SpotRole.STAY, "Kitchen", List.of(21L)))))),
                "evening: Kitchen is not a room of the house"),
            Arguments.of(new SimulationPlan(List.of(evening(2, null, null, List.of(new Spot(SpotRole.STAY, "kitchen", List.of(21L, 21L)))))),
                "evening: a light is twice in a spot"),
            Arguments.of(new SimulationPlan(List.of(evening(2, 1, 2, List.of(KITCHEN, new Spot(SpotRole.CLOSING, "kitchen", List.of(21L)))))),
                "evening: a closing spot belongs to a continuous phase, not to visits"),
            Arguments.of(new SimulationPlan(List.of(new PhasePlan(PhaseName.NIGHT, true, LocalTime.of(1, 0), LocalTime.of(1, 0),
                15, 3, 8, 1, 1, 2, List.of(KITCHEN)))), "night: from and to are the same time"),
            Arguments.of(new SimulationPlan(List.of(new PhasePlan(PhaseName.NIGHT, true, LocalTime.of(1, 0), LocalTime.of(5, 0),
                15, 9, 8, 1, 1, 2, List.of(KITCHEN)))), "night: minStayMinutes is not above maxStayMinutes")
        );
    }

    @ParameterizedTest
    @MethodSource("broken")
    void should_refuse_a_broken_plan_with_its_reason(final SimulationPlan plan, final String reason) {
        assertThatThrownBy(() -> PlanRules.check(plan))
            .isInstanceOfSatisfying(LightException.class, e -> {
                assertThat(e.getError()).isEqualTo(LightError.INVALID_PLAN);
                assertThat(e.getMessage()).isEqualTo(reason);
            });
    }

    //every light of a spot a light of the registry, in the room of the spot
    @Test
    void should_refuse_a_light_that_is_not_in_the_room_of_its_spot_or_not_a_light() {
        assertThatCode(() -> PlanRules.checkLights(new SimulationPlan(List.of(evening(2, null, null, List.of(KITCHEN, HALL)))), LIGHTS))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> PlanRules.checkLights(new SimulationPlan(List.of(evening(2, null, null,
            List.of(new Spot(SpotRole.STAY, "kitchen", List.of(HALL_LEFT.id())))))), LIGHTS))
            .hasMessage("light 120 (Left) is in the hall up, not in the kitchen");
        assertThatThrownBy(() -> PlanRules.checkLights(new SimulationPlan(List.of(evening(2, null, null,
            List.of(new Spot(SpotRole.STAY, "kitchen", List.of(999L)))))), LIGHTS))
            .hasMessage("light 999 of the evening is not a light of the registry");
    }

    private static PhasePlan evening(final int maxSpots, final Integer visitsMin, final Integer visitsMax, final List<Spot> spots) {
        return new PhasePlan(PhaseName.EVENING, true, LocalTime.of(16, 0), LocalTime.of(0, 30), 15, 10, 40, maxSpots,
            visitsMin, visitsMax, spots);
    }
}
