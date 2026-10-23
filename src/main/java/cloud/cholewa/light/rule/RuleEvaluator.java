package cloud.cholewa.light.rule;

import cloud.cholewa.light.model.CommandSource;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightStatus;
import cloud.cholewa.light.model.RoomOccupancy;
import cloud.cholewa.light.model.rule.MotionPeriod;
import cloud.cholewa.light.model.rule.RoomRule;
import cloud.cholewa.light.occupancy.SensorKind;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

//What the rule of a room makes of the room as it is now (HAS-262): pure, from the rule, the occupancy, the
//lights with their state and the clock - no I/O, pinned by RuleEvaluatorTest. The engine never fights a
//person and never acts on a room it cannot judge:
//- a manual hold: a light whose last change was not this service's (a wall switch, the Shelly app, an AMX
//  panel, the dashboard) is left alone for manualHoldMinutes after that change - derived from the state,
//  so it survives a restart with the state;
//- once: a light this service sent a command to is not sent another until it reported a change - an
//  unreachable light is not commanded every minute;
//- auto-off: every light that is on, or not known to be off (HAS-308), and not held goes off autoOffDelayMinutes
//  after the room is empty - by its presence sensors (and no motion since), or with motion sensors only by
//  the last motion. A room whose presence sensor is not known, or whose motion sensor is not reachable or
//  has not reported since the start, is skipped and named;
//- motion-on: at a fresh motion in a room darker than onlyBelowLux (a lux not known never holds), the
//  lights that are not on get what the period of the moment gives - no period, no light;
//- motion-off: motionOffAfterMinutes after the last motion the lights the rule switched on go off, unless a
//  presence sensor of the room says somebody is there or is not known.
//A rule switched off by the resident does nothing
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuleEvaluator {

    //a motion older than this is history - a read of a gateway after a start brings the last motion it heard
    static final Duration FRESH_MOTION = Duration.ofMinutes(2);

    //the sources of this service's own commands; every other change is a person's or a device's
    private static final Set<String> OWN_SOURCES = Set.of(CommandSource.RULE.value(), CommandSource.EMPTY_HOUSE.value());

    /** What the engine knows of a room: its rule, its occupancy and its lights. */
    public record RoomSnapshot(RoomRule rule, RoomOccupancy occupancy, List<LightSnapshot> lights) {
    }

    /** A light with its state and the last time the engine sent it a command (null: never since the start). */
    public record LightSnapshot(Light light, LightStatus status, LocalDateTime commandedAt) {
    }

    /** A light to switch, and why; {@code period} is the scheme of a motion-on. */
    public record RuleAction(Light light, boolean on, RuleReason reason, MotionPeriod period) {
    }

    /** The commands of a pass, and why auto-off could not judge the room - null when it could. */
    public record Verdict(List<RuleAction> actions, String skipped) {

        static final Verdict NONE = new Verdict(List.of(), null);
    }

    /** The pass of every minute: auto-off and motion-off. */
    public static Verdict tick(final RoomSnapshot room, final LocalDateTime now) {
        final RoomRule rule = room.rule();
        if (!active(rule)) {
            return Verdict.NONE;
        }
        final Map<Long, RuleAction> actions = new LinkedHashMap<>();
        String skipped = null;

        if (rule.autoOffEnabled() && rule.autoOffDelayMinutes() != null) {
            final Emptiness empty = emptiness(room.occupancy());
            if (empty.unknown() != null) {
                skipped = empty.unknown();
            } else if (empty.since() != null && !empty.since().plusMinutes(rule.autoOffDelayMinutes()).isAfter(now)) {
                //a light not known to be off goes off too (HAS-308, owner 2026-10-22): an off to a light that may
                //be on is harmless, and it would have switched off the kitchen bar the AMX could not confirm
                room.lights().stream()
                    .filter(light -> !Boolean.FALSE.equals(light.status().on()))
                    .filter(light -> free(light, rule, now))
                    .forEach(light -> actions.put(light.light().id(),
                        new RuleAction(light.light(), false, RuleReason.AUTO_OFF, null)));
            }
        }

        if (rule.motionOnEnabled() && rule.motionOffAfterMinutes() != null && motionOver(room.occupancy(), rule, now)) {
            room.lights().stream()
                .filter(light -> Boolean.TRUE.equals(light.status().on()))
                .filter(light -> CommandSource.RULE.value().equals(light.status().source()))
                .filter(light -> free(light, rule, now))
                .forEach(light -> actions.putIfAbsent(light.light().id(),
                    new RuleAction(light.light(), false, RuleReason.MOTION_OFF, null)));
        }

        return new Verdict(new ArrayList<>(actions.values()), skipped);
    }

    /** A motion was just seen in the room: motion-on. */
    public static List<RuleAction> motion(final RoomSnapshot room, final LocalDateTime now) {
        final RoomRule rule = room.rule();
        if (!active(rule) || !rule.motionOnEnabled()) {
            return List.of();
        }
        final RoomOccupancy occupancy = room.occupancy();
        final LocalDateTime lastMotionAt = occupancy.lastMotionAt();
        if (lastMotionAt == null || lastMotionAt.isBefore(now.minus(FRESH_MOTION))) {
            return List.of();
        }
        if (rule.onlyBelowLux() != null && occupancy.lux() != null && occupancy.lux() >= rule.onlyBelowLux()) {
            return List.of();
        }
        final Optional<MotionPeriod> period = periodAt(rule.periods(), now.toLocalTime());
        if (period.isEmpty()) {
            return List.of();
        }
        return room.lights().stream()
            .filter(light -> !Boolean.TRUE.equals(light.status().on()))
            .filter(light -> free(light, rule, now))
            .map(light -> new RuleAction(light.light(), true, RuleReason.MOTION_ON, period.get()))
            .toList();
    }

    /**
     * Until when a light is left alone after a change that was not this service's, by the rule of its room;
     * null when nothing holds it - no rule, no change known, or a change of this service.
     */
    public static LocalDateTime heldUntil(final LightStatus status, final RoomRule rule) {
        if (rule == null || status.changedAt() == null || status.source() == null || OWN_SOURCES.contains(status.source())) {
            return null;
        }
        return status.changedAt().plusMinutes(rule.manualHoldMinutes());
    }

    //Half-open, as RoomRuleRules checks them: a to before from crosses midnight, the same time is the whole day
    static Optional<MotionPeriod> periodAt(final List<MotionPeriod> periods, final LocalTime time) {
        return periods.stream()
            .filter(period -> {
                if (period.from().equals(period.to())) {
                    return true;
                }
                if (period.from().isBefore(period.to())) {
                    return !time.isBefore(period.from()) && time.isBefore(period.to());
                }
                return !time.isBefore(period.from()) || time.isBefore(period.to());
            })
            .findFirst();
    }

    private static boolean active(final RoomRule rule) {
        return rule != null && rule.enabledByResident();
    }

    //neither held by a person nor waiting for the change of a command already sent
    private static boolean free(final LightSnapshot light, final RoomRule rule, final LocalDateTime now) {
        final LocalDateTime heldUntil = heldUntil(light.status(), rule);
        if (heldUntil != null && heldUntil.isAfter(now)) {
            return false;
        }
        final LocalDateTime commandedAt = light.commandedAt();
        return commandedAt == null
            || light.status().changedAt() != null && light.status().changedAt().isAfter(commandedAt);
    }

    //The motion is over and nobody is known to be there: a presence sensor that says somebody is, or is not
    //known, keeps the lights on; a last motion not known (a start) decides nothing
    private static boolean motionOver(final RoomOccupancy occupancy, final RoomRule rule, final LocalDateTime now) {
        if (occupancy.lastMotionAt() == null
            || occupancy.lastMotionAt().plusMinutes(rule.motionOffAfterMinutes()).isAfter(now)) {
            return false;
        }
        return !hasPresence(occupancy) || Boolean.FALSE.equals(occupancy.occupied());
    }

    //Since when the room is empty, or why that is not known. since null: somebody is there
    private static Emptiness emptiness(final RoomOccupancy occupancy) {
        if (hasPresence(occupancy)) {
            if (occupancy.occupied() == null) {
                return new Emptiness(null, "its presence sensor is not known");
            }
            if (Boolean.TRUE.equals(occupancy.occupied())) {
                return new Emptiness(null, null);
            }
            //nobody by the presence sensor, and no motion since it said so
            final LocalDateTime since = occupancy.lastMotionAt() != null && occupancy.lastMotionAt().isAfter(occupancy.since())
                ? occupancy.lastMotionAt()
                : occupancy.since();
            return new Emptiness(since, null);
        }
        if (occupancy.sensors().stream().anyMatch(sensor -> Boolean.FALSE.equals(sensor.reachable()))) {
            return new Emptiness(null, "a motion sensor is not reachable");
        }
        if (occupancy.lastMotionAt() == null) {
            return new Emptiness(null, "no motion was seen since the start");
        }
        return new Emptiness(occupancy.lastMotionAt(), null);
    }

    private static boolean hasPresence(final RoomOccupancy occupancy) {
        return occupancy.sensors().stream().anyMatch(sensor -> SensorKind.PRESENCE.value().equals(sensor.kind()));
    }

    private record Emptiness(LocalDateTime since, String unknown) {
    }
}
