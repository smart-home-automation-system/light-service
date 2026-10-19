package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.light.model.CommandSource;
import cloud.cholewa.light.rule.RuleEvaluator.LightSnapshot;
import cloud.cholewa.light.rule.RuleEvaluator.RuleAction;
import cloud.cholewa.light.rule.RuleReason;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

//What the empty house makes of the lights (HAS-263): pure, from their state and what should be lit now - by
//day nothing, by night what the simulation lights at this minute - pinned by EmptyHouseEvaluatorTest.
//- a light that should be lit and is not known to be on goes on; any other light known to be on goes off -
//  the lights left on by day, the forgotten ones and the simulation's own at night;
//- never against a person: a light somebody switched after the house became empty - from a wall switch, the
//  dashboard, the Shelly app; a cleaner or a guest the household registry does not know - is left alone until
//  the house is occupied again. Before it, the household was there: a light they left on is a forgotten one;
//- once, as for the rules: a light commanded is not commanded again until it reported a change.
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EmptyHouseEvaluator {

    private static final Set<String> OWN_SOURCES = Set.of(CommandSource.RULE.value(), CommandSource.EMPTY_HOUSE.value());

    public static List<RuleAction> decide(final List<LightSnapshot> lights, final Set<Long> lit, final boolean night,
                                          final LocalDateTime emptySince) {
        final RuleReason reason = night ? RuleReason.PRESENCE_SIMULATION : RuleReason.EMPTY_HOUSE;
        return lights.stream()
            .filter(EmptyHouseEvaluator::free)
            .filter(light -> !switchedByAPerson(light, emptySince))
            .flatMap(light -> {
                final boolean wanted = lit.contains(light.light().id());
                if (wanted && !Boolean.TRUE.equals(light.status().on())) {
                    return Stream.of(new RuleAction(light.light(), true, reason, null));
                }
                if (!wanted && Boolean.TRUE.equals(light.status().on())) {
                    return Stream.of(new RuleAction(light.light(), false, reason, null));
                }
                return Stream.<RuleAction>empty();
            })
            .toList();
    }

    //a change after the house became empty that was not this service's
    private static boolean switchedByAPerson(final LightSnapshot light, final LocalDateTime emptySince) {
        return emptySince != null
            && light.status().changedAt() != null
            && light.status().changedAt().isAfter(emptySince)
            && light.status().source() != null
            && !OWN_SOURCES.contains(light.status().source());
    }

    private static boolean free(final LightSnapshot light) {
        return light.commandedAt() == null
            || light.status().changedAt() != null && light.status().changedAt().isAfter(light.commandedAt());
    }
}
