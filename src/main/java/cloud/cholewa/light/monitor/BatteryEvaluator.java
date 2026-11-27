package cloud.cholewa.light.monitor;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

//What the battery watch makes of the BLU Motions and the warnings already given (HAS-530): pure, pinned by
//BatteryEvaluatorTest.
//- a level below lowBelow and not warned about: a warning - once, never a reminder (a battery runs down over weeks);
//- warned about and its level known at lowBelow + BACK_MARGIN or above: back - a replaced battery, an info. The margin
//  keeps a level wavering at the threshold (19 % at night, 20 % by day) from a warning and an info every swing;
//- a level not known is never low and never back: after a start, or a gateway that does not say, nothing is decided;
//- warned about and no longer watched (removed from the registry): forgotten without a word.
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BatteryEvaluator {

    /** The points above the threshold a battery must be to count as back - a replaced battery reports far above it. */
    public static final int BACK_MARGIN = 10;

    /** A BLU Motion with its battery in per cent, null while not known. */
    public record Level(long id, String name, String room, Integer level) {
    }

    /** A warning the technical channel had. */
    public record Warning(long id, String name, String room, int level, LocalDateTime since) {
    }

    /** What is due: new warnings, the sensors back above the threshold (with their level now), the ones forgotten. */
    public record Decision(List<Warning> warnings, List<Level> returns, List<Warning> forgotten) {

        public boolean nothing() {
            return warnings.isEmpty() && returns.isEmpty() && forgotten.isEmpty();
        }
    }

    public static Decision decide(final List<Level> levels, final Map<Long, Warning> open, final LocalDateTime now,
                                  final int lowBelow) {
        final List<Warning> warnings = new ArrayList<>();
        final List<Level> returns = new ArrayList<>();

        for (final Level sensor : levels) {
            if (sensor.level() == null) {
                continue;
            }
            final boolean warned = open.containsKey(sensor.id());
            if (!warned && sensor.level() < lowBelow) {
                warnings.add(new Warning(sensor.id(), sensor.name(), sensor.room(), sensor.level(), now));
            } else if (warned && sensor.level() >= lowBelow + BACK_MARGIN) {
                returns.add(sensor);
            }
        }
        final Set<Long> ids = levels.stream().map(Level::id).collect(Collectors.toSet());
        final List<Warning> forgotten = open.values().stream().filter(warning -> !ids.contains(warning.id())).toList();
        return new Decision(warnings, returns, forgotten);
    }
}
