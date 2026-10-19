package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.light.database.repository.SimulationSpotRepository;
import cloud.cholewa.light.error.LightError;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.emptyhouse.PhaseName;
import cloud.cholewa.light.model.emptyhouse.PhasePlan;
import cloud.cholewa.light.model.emptyhouse.SimulationPlan;
import cloud.cholewa.light.model.emptyhouse.Spot;
import cloud.cholewa.light.model.emptyhouse.SpotRole;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static cloud.cholewa.light.rule.RoomRuleFixtures.BEDROOM_CEILING;
import static cloud.cholewa.light.rule.RoomRuleFixtures.HALL_LEFT;
import static cloud.cholewa.light.rule.RoomRuleFixtures.KITCHEN_CEILING;
import static cloud.cholewa.light.rule.RoomRuleFixtures.LIGHTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

//The plan of the presence simulation on the tables of V4 (HAS-263), in a PostgreSQL of Testcontainers like
//RoomRuleServiceTest: what is stored is what is read back, in the order of the phases and of the spots; a plan
//that is refused, or a write that fails, leaves the stored one as it was. Without a Docker it fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import({SimulationPlanService.class, SimulationPlanServiceTest.HouseClock.class})
class SimulationPlanServiceTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final Spot KITCHEN = new Spot(SpotRole.STAY, "kitchen", List.of(KITCHEN_CEILING.id()));
    private static final Spot BEDROOM = new Spot(SpotRole.STAY, "bedroom", List.of(BEDROOM_CEILING.id()));
    private static final Spot HALL = new Spot(SpotRole.PATH, "hall up", List.of(HALL_LEFT.id()));
    private static final PhasePlan EVENING = new PhasePlan(PhaseName.EVENING, true, LocalTime.of(16, 0), LocalTime.of(0, 30),
        15, 10, 40, 2, null, null, List.of(KITCHEN, HALL, BEDROOM));
    private static final PhasePlan NIGHT = new PhasePlan(PhaseName.NIGHT, false, LocalTime.of(0, 30), LocalTime.of(5, 0),
        10, 3, 8, 1, 1, 2, List.of(BEDROOM, HALL));

    @Autowired
    private SimulationPlanService sut;

    @MockitoSpyBean
    private SimulationSpotRepository spotRepository;

    @MockitoBean
    private LightRegistry lightRegistry;

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
    void forgetThePlan() {
        reset(spotRepository);
        org.mockito.Mockito.when(lightRegistry.all()).thenReturn(Mono.just(LIGHTS));
        sut.replace(SimulationPlan.NONE).block();
    }

    //the phases in the order they come, whatever the order written; the spots as written
    @Test
    void should_store_a_plan_and_answer_it_as_stored() {
        final SimulationPlan written = sut.replace(new SimulationPlan(List.of(NIGHT, EVENING))).block();

        assertThat(written).isEqualTo(new SimulationPlan(List.of(EVENING, NIGHT)));
        sut.get().as(StepVerifier::create).expectNext(written).verifyComplete();
    }

    @Test
    void should_answer_no_phases_without_a_plan() {
        sut.get().as(StepVerifier::create).expectNext(SimulationPlan.NONE).verifyComplete();
    }

    //removing the simulation has no light to judge: it needs no registry
    @Test
    void should_remove_the_plan_without_the_registry() {
        sut.replace(new SimulationPlan(List.of(EVENING))).block();
        org.mockito.Mockito.when(lightRegistry.all()).thenReturn(Mono.error(LightException.registryUnavailable(
            new IllegalStateException("database-service away"))));

        sut.replace(SimulationPlan.NONE).as(StepVerifier::create).expectNext(SimulationPlan.NONE).verifyComplete();
    }

    @Test
    void should_replace_a_plan_whole() {
        sut.replace(new SimulationPlan(List.of(EVENING, NIGHT))).block();

        sut.replace(new SimulationPlan(List.of(NIGHT))).as(StepVerifier::create)
            .expectNext(new SimulationPlan(List.of(NIGHT)))
            .verifyComplete();
    }

    //a light of another room: refused before anything is written
    @Test
    void should_store_nothing_of_a_refused_plan() {
        sut.replace(new SimulationPlan(List.of(EVENING))).block();

        sut.replace(new SimulationPlan(List.of(new PhasePlan(PhaseName.MORNING, true, LocalTime.of(5, 0), LocalTime.of(8, 0),
                15, 5, 20, 2, null, null, List.of(new Spot(SpotRole.STAY, "kitchen", List.of(HALL_LEFT.id())))))))
            .as(StepVerifier::create)
            .verifyErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(LightException.class,
                e -> assertThat(e.getError()).isEqualTo(LightError.INVALID_PLAN)));
        sut.get().as(StepVerifier::create).expectNext(new SimulationPlan(List.of(EVENING))).verifyComplete();
    }

    //the spots fail to be written after the old plan was deleted and the new phase inserted: the transaction
    //rolls the whole plan back
    @Test
    void should_leave_a_plan_as_it_was_when_a_write_fails() {
        sut.replace(new SimulationPlan(List.of(NIGHT))).block();
        doAnswer(invocation -> Mono.error(new IllegalStateException("connection lost"))).when(spotRepository).save(any());

        sut.replace(new SimulationPlan(List.of(EVENING))).as(StepVerifier::create)
            .verifyError(IllegalStateException.class);
        reset(spotRepository);
        sut.get().as(StepVerifier::create).expectNext(new SimulationPlan(List.of(NIGHT))).verifyComplete();
    }

    @TestConfiguration
    static class HouseClock {

        @Bean
        Clock clock() {
            return Clock.system(ZoneId.of("Europe/Warsaw"));
        }
    }
}
