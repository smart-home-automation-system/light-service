package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.light.emptyhouse.SimulationPlanner.Night;
import cloud.cholewa.light.emptyhouse.SimulationPlanner.Segment;
import cloud.cholewa.light.model.emptyhouse.PhaseName;
import cloud.cholewa.light.model.emptyhouse.PhasePlan;
import cloud.cholewa.light.model.emptyhouse.SimulationPlan;
import cloud.cholewa.light.model.emptyhouse.Spot;
import cloud.cholewa.light.model.emptyhouse.SpotRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

//The night of the presence simulation without I/O (HAS-263): the plan, the sun and the date in, the lit
//segments out. The properties are checked over sixty nights each, winter and summer: one example proves little
//of a random night
class SimulationPlannerTest {

    private static final Spot LIVING = new Spot(SpotRole.STAY, "living room", List.of(24L, 25L));
    private static final Spot KITCHEN = new Spot(SpotRole.STAY, "kitchen", List.of(21L));
    private static final Spot OFFICE = new Spot(SpotRole.STAY, "office", List.of(87L));
    private static final Spot HALL_DOWN = new Spot(SpotRole.PATH, "hall down", List.of(88L));
    private static final Spot BATHROOM = new Spot(SpotRole.STAY, "bathroom up", List.of(42L));
    private static final Spot BED_TIME = new Spot(SpotRole.CLOSING, "bathroom up", List.of(42L));
    private static final Spot HALL_UP = new Spot(SpotRole.PATH, "hall up", List.of(89L));

    private static final PhasePlan EVENING = new PhasePlan(PhaseName.EVENING, true, LocalTime.of(16, 0), LocalTime.of(0, 30),
        15, 10, 40, 2, null, null, List.of(LIVING, KITCHEN, OFFICE, HALL_DOWN, BED_TIME));
    private static final PhasePlan NIGHT = new PhasePlan(PhaseName.NIGHT, true, LocalTime.of(0, 30), LocalTime.of(5, 0),
        15, 3, 8, 1, 1, 2, List.of(BATHROOM, HALL_UP));
    private static final PhasePlan MORNING = new PhasePlan(PhaseName.MORNING, true, LocalTime.of(5, 0), LocalTime.of(8, 0),
        15, 5, 20, 2, null, null, List.of(BATHROOM, KITCHEN, HALL_DOWN));
    private static final SimulationPlan PLAN = new SimulationPlan(List.of(EVENING, NIGHT, MORNING));

    //a winter night: dusk at 16:30, sunrise at 07:30 - all three phases fit
    private static final LocalDate WINTER = LocalDate.of(2026, 12, 1);
    //a summer night: dusk at 21:40, sunrise at 04:20 - no morning before the sunrise
    private static final LocalDate SUMMER = LocalDate.of(2026, 6, 20);

    @Test
    void should_plan_the_same_night_for_the_same_date_and_another_for_another() {
        assertThat(winter(WINTER).segments()).isEqualTo(winter(WINTER).segments());
        assertThat(winter(WINTER).segments()).isNotEqualTo(winter(WINTER.plusDays(1)).segments());
    }

    //the evening from the dusk, never before; somebody always somewhere until its end, moved by up to 15 minutes
    @Test
    void should_keep_somebody_in_a_living_room_from_the_dusk_to_the_end_of_the_evening() {
        nights(WINTER).forEach(night -> {
            final List<Segment> stays = of(night, PhaseName.EVENING, SpotRole.STAY);
            final LocalDateTime start = stays.getFirst().from();
            final LocalDateTime end = stays.stream().map(Segment::to).max(LocalDateTime::compareTo).orElseThrow();

            assertThat(start).isEqualTo(night.dusk());
            assertThat(end).isBetween(night.date().plusDays(1).atTime(0, 15), night.date().plusDays(1).atTime(0, 45));
            minutes(start, end).forEach(minute -> assertThat(stays).anyMatch(segment -> segment.covers(minute)));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void should_light_at_most_max_spots_at_once(final int maxSpotsAtOnce) {
        final PhasePlan evening = new PhasePlan(PhaseName.EVENING, true, LocalTime.of(16, 0), LocalTime.of(0, 30),
            15, 10, 40, maxSpotsAtOnce, null, null, List.of(LIVING, KITCHEN, OFFICE));
        nights(WINTER, new SimulationPlan(List.of(evening))).forEach(night ->
            minutes(night.dusk(), night.date().plusDays(1).atTime(0, 45)).forEach(minute ->
                assertThat(night.segments().stream().filter(segment -> segment.covers(minute)).count())
                    .isLessThanOrEqualTo(maxSpotsAtOnce)));
    }

    //each stay 10 to 40 minutes, the last one cut by the end of the phase
    @Test
    void should_keep_every_stay_within_its_bounds() {
        nights(WINTER).forEach(night -> of(night, PhaseName.EVENING, SpotRole.STAY).stream()
            .filter(segment -> segment.from().isBefore(night.date().plusDays(1).atTime(0, 0)))
            .forEach(segment -> assertThat(Duration.between(segment.from(), segment.to()).toMinutes()).isBetween(1L, 40L)));
    }

    //the hall for the two minutes around a move, the last light before bed 5 to 15 minutes after the evening
    @Test
    void should_light_the_path_around_a_move_and_the_closing_after_the_evening() {
        nights(WINTER).forEach(night -> {
            assertThat(of(night, PhaseName.EVENING, SpotRole.PATH))
                .allSatisfy(path -> assertThat(Duration.between(path.from(), path.to()).toMinutes()).isBetween(1L, 2L));
            final LocalDateTime end = of(night, PhaseName.EVENING, SpotRole.STAY).stream().map(Segment::to)
                .max(LocalDateTime::compareTo).orElseThrow();
            assertThat(of(night, PhaseName.EVENING, SpotRole.CLOSING)).singleElement().satisfies(closing -> {
                assertThat(closing.from()).isEqualTo(end);
                assertThat(Duration.between(closing.from(), closing.to()).toMinutes()).isBetween(5L, 15L);
            });
        });
        assertThat(nights(WINTER).flatMap(night -> of(night, PhaseName.EVENING, SpotRole.PATH).stream())).isNotEmpty();
    }

    //one or two visits of 3 to 8 minutes between 00:15 and 05:15, the hall a minute before and after
    @Test
    void should_plan_the_night_as_short_visits() {
        nights(WINTER).forEach(night -> {
            final List<Segment> visits = of(night, PhaseName.NIGHT, SpotRole.STAY);
            assertThat(visits).hasSizeBetween(1, 2).allSatisfy(visit -> {
                assertThat(Duration.between(visit.from(), visit.to()).toMinutes()).isBetween(3L, 8L);
                assertThat(visit.from()).isAfterOrEqualTo(night.date().plusDays(1).atTime(0, 15));
                assertThat(visit.to()).isBeforeOrEqualTo(night.date().plusDays(1).atTime(5, 15));
                assertThat(of(night, PhaseName.NIGHT, SpotRole.PATH))
                    .anyMatch(path -> !path.from().isAfter(visit.from()) && !path.to().isBefore(visit.to()));
            });
        });
    }

    //a summer night ends at the sunrise: nothing lit after it, and no morning - it would start after the sunrise
    @Test
    void should_end_everything_at_the_sunrise() {
        nights(SUMMER, PLAN, LocalTime.of(21, 40), LocalTime.of(4, 20)).forEach(night -> {
            assertThat(night.segments()).allMatch(segment -> !segment.to().isAfter(night.sunrise()));
            assertThat(night.segments()).noneMatch(segment -> segment.phase() == PhaseName.MORNING);
            assertThat(night.segments()).allMatch(segment -> !segment.from().isBefore(night.dusk()));
        });
    }

    //a winter morning to 08:00 at the latest, cut by a sunrise at 07:30
    @Test
    void should_end_the_morning_at_the_sunrise_when_it_comes_first() {
        nights(WINTER).forEach(night -> {
            final List<Segment> morning = of(night, PhaseName.MORNING, SpotRole.STAY);
            assertThat(morning).isNotEmpty();
            assertThat(morning.stream().map(Segment::to).max(LocalDateTime::compareTo).orElseThrow()).isEqualTo(night.sunrise());
            assertThat(morning.getFirst().from()).isBetween(night.date().plusDays(1).atTime(4, 45),
                night.date().plusDays(1).atTime(5, 15));
        });
    }

    @Test
    void should_light_nothing_of_a_phase_that_is_off_or_without_a_plan() {
        final PhasePlan off = new PhasePlan(PhaseName.EVENING, false, LocalTime.of(16, 0), LocalTime.of(0, 30),
            15, 10, 40, 2, null, null, List.of(LIVING));

        assertThat(winter(WINTER, new SimulationPlan(List.of(off))).segments()).isEmpty();
        assertThat(winter(WINTER, SimulationPlan.NONE).segments()).isEmpty();
    }

    //a day without a dusk or a sunrise is no night
    @Test
    void should_have_no_night_without_a_dusk() {
        final Night night = SimulationPlanner.night(WINTER, new SunDay(WINTER, LocalTime.of(7, 0), null),
            new SunDay(WINTER.plusDays(1), LocalTime.of(7, 30), LocalTime.of(16, 30)), PLAN);

        assertThat(night.segments()).isEmpty();
        assertThat(night.contains(WINTER.atTime(22, 0))).isFalse();
    }

    @Test
    void should_take_a_time_before_noon_for_the_night_before() {
        assertThat(SimulationPlanner.nightOf(WINTER.atTime(3, 0))).isEqualTo(WINTER.minusDays(1));
        assertThat(SimulationPlanner.nightOf(WINTER.atTime(12, 0))).isEqualTo(WINTER);
        assertThat(SimulationPlanner.nightOf(WINTER.atTime(23, 59))).isEqualTo(WINTER);
    }

    //what is lit at a time, its phase and when that changes next
    @Test
    void should_answer_what_is_lit_at_a_time() {
        final Night night = winter(WINTER);
        final Segment first = of(night, PhaseName.EVENING, SpotRole.STAY).getFirst();

        assertThat(night.lightsAt(first.from())).containsAll(first.lights());
        assertThat(night.phaseAt(first.from())).isEqualTo(PhaseName.EVENING);
        assertThat(night.lightsAt(night.dusk().minusMinutes(1))).isEmpty();
        assertThat(night.nextChangeAfter(night.dusk().minusMinutes(1))).contains(night.dusk());
        assertThat(night.contains(night.dusk())).isTrue();
        assertThat(night.contains(night.sunrise())).isFalse();
    }

    private static Night winter(final LocalDate date) {
        return winter(date, PLAN);
    }

    private static Night winter(final LocalDate date, final SimulationPlan plan) {
        return night(date, plan, LocalTime.of(16, 30), LocalTime.of(7, 30));
    }

    private static Stream<Night> nights(final LocalDate from) {
        return nights(from, PLAN);
    }

    private static Stream<Night> nights(final LocalDate from, final SimulationPlan plan) {
        return nights(from, plan, LocalTime.of(16, 30), LocalTime.of(7, 30));
    }

    private static Stream<Night> nights(final LocalDate from, final SimulationPlan plan, final LocalTime dusk,
                                        final LocalTime sunrise) {
        return IntStream.range(0, 60).mapToObj(day -> night(from.plusDays(day), plan, dusk, sunrise));
    }

    private static Night night(final LocalDate date, final SimulationPlan plan, final LocalTime dusk, final LocalTime sunrise) {
        return SimulationPlanner.night(date, new SunDay(date, LocalTime.of(7, 0), dusk),
            new SunDay(date.plusDays(1), sunrise, dusk), plan);
    }

    private static List<Segment> of(final Night night, final PhaseName phase, final SpotRole role) {
        return night.segments().stream().filter(segment -> segment.phase() == phase && segment.role() == role).toList();
    }

    private static Stream<LocalDateTime> minutes(final LocalDateTime from, final LocalDateTime to) {
        return Stream.iterate(from, minute -> minute.isBefore(to), minute -> minute.plusMinutes(1));
    }
}
