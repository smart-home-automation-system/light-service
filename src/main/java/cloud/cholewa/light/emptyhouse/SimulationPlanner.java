package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.light.model.emptyhouse.PhaseName;
import cloud.cholewa.light.model.emptyhouse.PhasePlan;
import cloud.cholewa.light.model.emptyhouse.SimulationPlan;
import cloud.cholewa.light.model.emptyhouse.Spot;
import cloud.cholewa.light.model.emptyhouse.SpotRole;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

//The night of the presence simulation (HAS-263), worked out from the plan, the sun and the date - pure, with
//no I/O and no clock, pinned by SimulationPlannerTest. The same night always comes out the same (the random
//choices are seeded by the date), so a restart in the middle of a night picks up where it was, and a
//different night looks different: every bound moved at random, the stays chosen anew.
//- The night is the civil dusk of its date to the sunrise of the next; a phase runs only inside it, its own
//  from and to (after noon the evening, before noon the morning after) each moved by up to its jitter.
//- A continuous phase: somebody is always in a stay spot, staying minStay-maxStay minutes, then moving to
//  another. Sometimes a second stay is lit for a while, up to maxSpotsAtOnce. A path spot (the hall) is lit
//  for the two minutes around every other move, and a closing spot for 5 to 15 minutes after the end of the
//  phase - the last light before bed.
//- A visits phase: visitsMin-visitsMax visits spread over the phase, each a stay of minStay-maxStay minutes
//  with its path lit a minute before and after; dark between them.
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SimulationPlanner {

    private static final LocalTime NOON = LocalTime.NOON;
    private static final double SECOND_STAY = 0.35;
    private static final double PATH_ON_A_MOVE = 0.5;
    private static final int CLOSING_MIN = 5;
    private static final int CLOSING_MAX = 15;

    /** A spot lit from (included) to (not), in a phase. */
    public record Segment(PhaseName phase, SpotRole role, String room, List<Long> lights, LocalDateTime from, LocalDateTime to) {

        boolean covers(final LocalDateTime time) {
            return !time.isBefore(from) && time.isBefore(to);
        }
    }

    /** The night of a date: when it starts and ends, and what is lit when; no segments without a dusk or a sunrise. */
    public record Night(LocalDate date, LocalDateTime dusk, LocalDateTime sunrise, List<Segment> segments) {

        /** Whether a time is in this night - between its dusk and its sunrise. */
        public boolean contains(final LocalDateTime time) {
            return dusk != null && sunrise != null && !time.isBefore(dusk) && time.isBefore(sunrise);
        }

        /** The lights lit at a time. */
        public Set<Long> lightsAt(final LocalDateTime time) {
            final Set<Long> lit = new LinkedHashSet<>();
            segments.stream().filter(segment -> segment.covers(time)).forEach(segment -> lit.addAll(segment.lights()));
            return lit;
        }

        /** The phase of a time - the one whose segment covers it, or null between them. */
        public PhaseName phaseAt(final LocalDateTime time) {
            return segments.stream().filter(segment -> segment.covers(time)).map(Segment::phase).findFirst().orElse(null);
        }

        /** The next time after the given one at which what is lit changes. */
        public Optional<LocalDateTime> nextChangeAfter(final LocalDateTime time) {
            return segments.stream()
                .flatMap(segment -> Stream.of(segment.from(), segment.to()))
                .filter(boundary -> boundary.isAfter(time))
                .min(Comparator.naturalOrder());
        }
    }

    /** The night that a time belongs to: before noon the night of the day before. */
    public static LocalDate nightOf(final LocalDateTime time) {
        return time.toLocalTime().isBefore(NOON) ? time.toLocalDate().minusDays(1) : time.toLocalDate();
    }

    public static Night night(final LocalDate date, final SunDay today, final SunDay tomorrow, final SimulationPlan plan) {
        if (today.dusk() == null || tomorrow.sunrise() == null) {
            return new Night(date, null, null, List.of());
        }
        final LocalDateTime dusk = date.atTime(today.dusk());
        final LocalDateTime sunrise = date.plusDays(1).atTime(tomorrow.sunrise());
        final Random random = new Random(date.toEpochDay());
        final List<Segment> segments = new ArrayList<>();

        plan.phases().stream()
            .filter(PhasePlan::enabled)
            .sorted(Comparator.comparing(PhasePlan::phase))
            .forEach(phase -> {
                final LocalDateTime start = latest(shifted(date, phase.from(), phase.jitterMinutes(), random), dusk);
                final LocalDateTime end = earliest(shifted(date, phase.to(), phase.jitterMinutes(), random), sunrise);
                if (!start.isBefore(end)) {
                    return;
                }
                if (phase.visits()) {
                    visits(phase, start, end, random, segments);
                } else {
                    continuous(phase, start, end, sunrise, random, segments);
                }
            });
        return new Night(date, dusk, sunrise, List.copyOf(segments));
    }

    private static void continuous(final PhasePlan phase, final LocalDateTime start, final LocalDateTime end,
                                   final LocalDateTime sunrise, final Random random, final List<Segment> segments) {
        final List<Spot> stays = of(phase, SpotRole.STAY);
        final List<Spot> paths = of(phase, SpotRole.PATH);
        final List<Spot> closings = of(phase, SpotRole.CLOSING);

        LocalDateTime time = start;
        Spot previous = null;
        while (time.isBefore(end)) {
            final Spot stay = pick(stays, previous, random);
            final int minutes = between(phase.minStayMinutes(), phase.maxStayMinutes(), random);
            final LocalDateTime left = earliest(time.plusMinutes(minutes), end);
            segments.add(segment(phase, stay, time, left));

            if (phase.maxSpotsAtOnce() > 1 && stays.size() > 1 && random.nextDouble() < SECOND_STAY) {
                final Spot second = pick(stays, stay, random);
                final LocalDateTime from = time.plusMinutes(random.nextInt(Math.max(1, minutes / 2)));
                final LocalDateTime to = earliest(from.plusMinutes(Math.max(1, between(phase.minStayMinutes(),
                    phase.maxStayMinutes(), random) / 2)), left);
                if (from.isBefore(to)) {
                    segments.add(segment(phase, second, from, to));
                }
            }
            if (left.isBefore(end) && !paths.isEmpty() && random.nextDouble() < PATH_ON_A_MOVE) {
                segments.add(segment(phase, pick(paths, null, random), left.minusMinutes(1), earliest(left.plusMinutes(1), end)));
            }
            previous = stay;
            time = left;
        }

        if (!closings.isEmpty()) {
            final LocalDateTime to = earliest(end.plusMinutes(between(CLOSING_MIN, CLOSING_MAX, random)), sunrise);
            if (end.isBefore(to)) {
                segments.add(segment(phase, pick(closings, null, random), end, to));
            }
        }
    }

    private static void visits(final PhasePlan phase, final LocalDateTime start, final LocalDateTime end,
                               final Random random, final List<Segment> segments) {
        final List<Spot> stays = of(phase, SpotRole.STAY);
        final List<Spot> paths = of(phase, SpotRole.PATH);
        final int count = between(phase.visitsMin(), phase.visitsMax(), random);
        final long slice = wallClockMinutes(start, end) / count;
        final long longest = Math.max(1, slice - 2);

        for (int visit = 0; visit < count; visit++) {
            //a short phase cuts the stay to its slice, never below a minute
            final int minutes = (int) Math.clamp(between(phase.minStayMinutes(), phase.maxStayMinutes(), random), 1, longest);
            final long room = slice - minutes - 2;
            final LocalDateTime from = start.plusMinutes(visit * slice + 1 + (room > 0 ? random.nextInt((int) room + 1) : 0));
            final LocalDateTime to = earliest(from.plusMinutes(minutes), end);
            if (!from.isBefore(to)) {
                continue;
            }
            segments.add(segment(phase, pick(stays, null, random), from, to));
            if (!paths.isEmpty()) {
                segments.add(segment(phase, pick(paths, null, random), latest(from.minusMinutes(1), start),
                    earliest(to.plusMinutes(1), end)));
            }
        }
    }

    //a time after noon is the evening of the night, before noon the morning after; moved by up to the jitter
    private static LocalDateTime shifted(final LocalDate date, final LocalTime time, final int jitter, final Random random) {
        final LocalDateTime at = time.isBefore(NOON) ? date.plusDays(1).atTime(time) : date.atTime(time);
        return at.plusMinutes(jitter == 0 ? 0 : random.nextInt(2 * jitter + 1) - jitter);
    }

    //The minutes between two times of the wall clock, as every bound of the plan is: the night of a change of
    //the clock is planned in the time on the clocks of the house, not in elapsed minutes
    static long wallClockMinutes(final LocalDateTime from, final LocalDateTime to) {
        return (to.toEpochSecond(ZoneOffset.UTC) - from.toEpochSecond(ZoneOffset.UTC)) / 60;
    }

    private static List<Spot> of(final PhasePlan phase, final SpotRole role) {
        return phase.spots().stream().filter(spot -> spot.role() == role).toList();
    }

    //another spot than the one before, when there is another
    private static Spot pick(final List<Spot> spots, final Spot except, final Random random) {
        final List<Spot> choice = spots.size() > 1 && except != null
            ? spots.stream().filter(spot -> spot != except).toList()
            : spots;
        return choice.get(random.nextInt(choice.size()));
    }

    private static int between(final int min, final int max, final Random random) {
        return min + random.nextInt(max - min + 1);
    }

    private static Segment segment(final PhasePlan phase, final Spot spot, final LocalDateTime from, final LocalDateTime to) {
        return new Segment(phase.phase(), spot.role(), spot.room(), spot.lights(), from, to);
    }

    private static LocalDateTime earliest(final LocalDateTime one, final LocalDateTime other) {
        return one.isBefore(other) ? one : other;
    }

    private static LocalDateTime latest(final LocalDateTime one, final LocalDateTime other) {
        return one.isAfter(other) ? one : other;
    }
}
