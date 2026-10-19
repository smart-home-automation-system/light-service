package cloud.cholewa.light.rule;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.database.model.MotionPeriodEntity;
import cloud.cholewa.light.database.repository.MotionPeriodRepository;
import cloud.cholewa.light.database.repository.RoomRuleRepository;
import cloud.cholewa.light.error.LightError;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.rule.MotionPeriod;
import cloud.cholewa.light.model.rule.RoomRule;
import cloud.cholewa.light.occupancy.OccupancyRegistry;
import cloud.cholewa.light.registry.LightRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static cloud.cholewa.light.rule.RoomRuleFixtures.LIGHTS;
import static cloud.cholewa.light.rule.RoomRuleFixtures.SENSORS;
import static cloud.cholewa.light.rule.RoomRuleFixtures.bedroomAutoOff;
import static cloud.cholewa.light.rule.RoomRuleFixtures.hallMotion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The rules on the tables of V2, in a PostgreSQL of Testcontainers like LightStateRepositoryTest (the
//upsert is PostgreSQL's own): what is stored is what is read back, a write that fails leaves the rule
//as it was, the switch of the resident survives the administrator. Without a Docker it fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import({RoomRuleService.class, RoomRuleServiceTest.MovableClock.class})
class RoomRuleServiceTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant EVENING = LocalDateTime.of(2026, 10, 19, 19, 30, 0, 123_456_789).atZone(HOUSE).toInstant();
    private static final LocalDateTime EVENING_AS_STORED = LocalDateTime.of(2026, 10, 19, 19, 30, 0, 123_456_000);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final AtomicReference<Instant> NOW = new AtomicReference<>(EVENING);

    @Autowired
    private RoomRuleService sut;

    @Autowired
    private RoomRuleRepository ruleRepository;

    @MockitoSpyBean
    private MotionPeriodRepository periodRepository;

    @MockitoBean
    private LightRegistry lightRegistry;

    @MockitoBean
    private OccupancyRegistry occupancyRegistry;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void forgetTheRules() {
        reset(periodRepository);
        NOW.set(EVENING);
        when(lightRegistry.all()).thenReturn(Mono.just(LIGHTS));
        when(occupancyRegistry.loaded()).thenReturn(true);
        when(occupancyRegistry.sensors()).thenReturn(SENSORS);
        ruleRepository.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    //the periods by the time they start, whatever the order they were given in
    @Test
    void should_store_a_rule_and_answer_it_as_stored() {
        final RoomRule written = sut.replace(RoomName.HALL_UP, hallMotion()).block();

        assertThat(written).isEqualTo(new RoomRule("hall up", true, 10, true, 30, 3,
            List.of(hallMotion().periods().get(1), hallMotion().periods().get(0)), 30, true, EVENING_AS_STORED));
        sut.get(RoomName.HALL_UP).as(StepVerifier::create).expectNext(written).verifyComplete();
    }

    @Test
    void should_keep_what_a_rule_leaves_out_as_left_out() {
        sut.replace(RoomName.BEDROOM, bedroomAutoOff()).as(StepVerifier::create)
            .assertNext(rule -> {
                assertThat(rule.onlyBelowLux()).isNull();
                assertThat(rule.motionOffAfterMinutes()).isNull();
                assertThat(rule.periods()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void should_answer_every_room_with_a_rule() {
        sut.replace(RoomName.HALL_UP, hallMotion()).block();
        sut.replace(RoomName.BEDROOM, bedroomAutoOff()).block();

        sut.all().as(StepVerifier::create)
            .assertNext(rules -> {
                assertThat(rules).extracting(RoomRule::room).containsExactly("bedroom", "hall up");
                assertThat(rules.get(0).periods()).isEmpty();
                assertThat(rules.get(1).periods()).hasSize(2);
            })
            .verifyComplete();
    }

    @Test
    void should_answer_no_rule_with_its_code() {
        sut.get(RoomName.KITCHEN).as(StepVerifier::create)
            .expectErrorSatisfies(refusal(LightError.NOT_FOUND_RULE))
            .verify();
        sut.remove(RoomName.KITCHEN).as(StepVerifier::create)
            .expectErrorSatisfies(refusal(LightError.NOT_FOUND_RULE))
            .verify();
        sut.switchByResident(RoomName.KITCHEN, false).as(StepVerifier::create)
            .expectErrorSatisfies(refusal(LightError.NOT_FOUND_RULE))
            .verify();
    }

    //whole: the periods are the new ones, the switch of the resident stays as the resident left it
    @Test
    void should_replace_a_rule_and_keep_the_switch_of_the_resident() {
        sut.replace(RoomName.HALL_UP, hallMotion()).block();
        sut.switchByResident(RoomName.HALL_UP, false).block();
        NOW.set(EVENING.plusSeconds(3600));

        final List<MotionPeriod> night = List.of(new MotionPeriod(LocalTime.of(23, 0), LocalTime.of(5, 0), 5, null, 200));
        final RoomRule replaced = sut.replace(RoomName.HALL_UP, RoomRuleFixtures.withPeriods(hallMotion(), night)).block();

        assertThat(replaced).isNotNull();
        assertThat(replaced.periods()).containsExactlyElementsOf(night);
        assertThat(replaced.enabledByResident()).isFalse();
        assertThat(replaced.updatedAt()).isEqualTo(EVENING_AS_STORED.plusHours(1));
        periodRepository.count().as(StepVerifier::create).expectNext(1L).verifyComplete();
    }

    @Test
    void should_store_nothing_of_a_refused_rule() {
        sut.replace(RoomName.KITCHEN, bedroomAutoOff()).as(StepVerifier::create)
            .expectErrorSatisfies(refusal(LightError.INVALID_RULE))
            .verify();

        ruleRepository.count().as(StepVerifier::create).expectNext(0L).verifyComplete();
    }

    //a rule is judged by the sensors of the registry: before one read nothing can be judged
    @Test
    void should_refuse_a_write_before_the_sensors_were_read() {
        when(occupancyRegistry.loaded()).thenReturn(false);

        sut.replace(RoomName.BEDROOM, bedroomAutoOff()).as(StepVerifier::create)
            .expectErrorSatisfies(refusal(LightError.REGISTRY_UNAVAILABLE))
            .verify();

        verifyNoInteractions(lightRegistry);
        ruleRepository.count().as(StepVerifier::create).expectNext(0L).verifyComplete();
    }

    //the row of the rule is written before the periods: a period that fails takes it back with it
    @Test
    void should_leave_a_rule_as_it_was_when_a_replace_fails() {
        final RoomRule written = sut.replace(RoomName.HALL_UP, hallMotion()).block();
        doAnswer(invocation -> Mono.error(new DataIntegrityViolationException("the disk is full")))
            .when(periodRepository).save(any(MotionPeriodEntity.class));
        NOW.set(EVENING.plusSeconds(3600));

        sut.replace(RoomName.HALL_UP, RoomRuleFixtures.withPeriods(hallMotion(), List.of(hallMotion().periods().get(1))))
            .as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();

        reset(periodRepository);
        sut.get(RoomName.HALL_UP).as(StepVerifier::create).expectNext(written).verifyComplete();
    }

    @Test
    void should_remove_a_rule_with_its_periods() {
        sut.replace(RoomName.HALL_UP, hallMotion()).block();

        sut.remove(RoomName.HALL_UP).as(StepVerifier::create).verifyComplete();

        ruleRepository.count().as(StepVerifier::create).expectNext(0L).verifyComplete();
        periodRepository.count().as(StepVerifier::create).expectNext(0L).verifyComplete();
    }

    //the same switch twice is written once: updatedAt says when it changed
    @Test
    void should_switch_a_rule_by_the_resident() {
        sut.replace(RoomName.BEDROOM, bedroomAutoOff()).block();
        NOW.set(EVENING.plusSeconds(60));

        sut.switchByResident(RoomName.BEDROOM, false).as(StepVerifier::create)
            .assertNext(rule -> {
                assertThat(rule.enabledByResident()).isFalse();
                assertThat(rule.updatedAt()).isEqualTo(EVENING_AS_STORED.plusMinutes(1));
            })
            .verifyComplete();

        NOW.set(EVENING.plusSeconds(120));
        sut.switchByResident(RoomName.BEDROOM, false).as(StepVerifier::create)
            .assertNext(rule -> assertThat(rule.updatedAt()).isEqualTo(EVENING_AS_STORED.plusMinutes(1)))
            .verifyComplete();
    }

    //the table keeps what one row can say even past the service
    @Test
    void should_refuse_a_delay_out_of_range_in_the_table() {
        ruleRepository.upsert("BEDROOM", true, 181, false, null, null, 60, EVENING_AS_STORED).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
        ruleRepository.upsert("BEDROOM", true, null, false, null, null, 60, EVENING_AS_STORED).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    private static Consumer<Throwable> refusal(final LightError error) {
        return thrown -> assertThat(thrown).isInstanceOfSatisfying(LightException.class,
            exception -> assertThat(exception.getError()).isEqualTo(error));
    }

    @TestConfiguration
    static class MovableClock {

        @Bean
        Clock clock() {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return HOUSE;
                }

                @Override
                public Clock withZone(final ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return NOW.get();
                }
            };
        }
    }
}
