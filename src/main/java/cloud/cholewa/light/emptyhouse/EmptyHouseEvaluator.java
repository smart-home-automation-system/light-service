package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.light.rule.RuleEvaluator.LightSnapshot;
import cloud.cholewa.light.rule.RuleEvaluator.RuleAction;
import cloud.cholewa.light.rule.RuleReason;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

//What the empty house makes of the lights (HAS-263): pure, from their state and what should be lit now - by
//day nothing, by night what the simulation lights at this minute - pinned by EmptyHouseEvaluatorTest.
//- a light that should be lit and is not known to be on goes on; any other light known to be on goes off -
//  the lights left on by day, the forgotten ones and the simulation's own at night;
//- no manual hold: nobody is at home to have switched a light by hand;
//- once, as for the rules: a light commanded is not commanded again until it reported a change.
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EmptyHouseEvaluator {

    public static List<RuleAction> decide(final List<LightSnapshot> lights, final Set<Long> lit, final boolean night) {
        final RuleReason reason = night ? RuleReason.PRESENCE_SIMULATION : RuleReason.EMPTY_HOUSE;
        return lights.stream()
            .filter(EmptyHouseEvaluator::free)
            .flatMap(light -> {
                final boolean wanted = lit.contains(light.light().id());
                if (wanted && !Boolean.TRUE.equals(light.status().on())) {
                    return java.util.stream.Stream.of(new RuleAction(light.light(), true, reason, null));
                }
                if (!wanted && Boolean.TRUE.equals(light.status().on())) {
                    return java.util.stream.Stream.of(new RuleAction(light.light(), false, reason, null));
                }
                return java.util.stream.Stream.empty();
            })
            .toList();
    }

    private static boolean free(final LightSnapshot light) {
        return light.commandedAt() == null
            || light.status().changedAt() != null && light.status().changedAt().isAfter(light.commandedAt());
    }
}
