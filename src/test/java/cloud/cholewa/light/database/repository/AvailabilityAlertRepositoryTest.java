package cloud.cholewa.light.database.repository;

import cloud.cholewa.light.database.model.AvailabilityAlertEntity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

//The open alerts of the availability monitor on the table of V5 (HAS-264), in a PostgreSQL of Testcontainers:
//a row inserted with its registry id as the key, a reminder moving last_alert_at, a kind the monitor does not
//know refused. Without a Docker it fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class AvailabilityAlertRepositoryTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 20, 12, 0);

    @Autowired
    private AvailabilityAlertRepository repository;

    @Autowired
    private R2dbcEntityTemplate entityTemplate;

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
    void forgetTheAlerts() {
        repository.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_keep_an_alert_and_move_it_with_a_reminder() {
        final AvailabilityAlertEntity alert = new AvailabilityAlertEntity(110L, "light", "Main", "tobi", NOON, NOON.plusMinutes(5));

        entityTemplate.insert(alert).as(StepVerifier::create).expectNext(alert).verifyComplete();
        repository.reminded(110L, NOON.plusHours(1)).as(StepVerifier::create).expectNext(1).verifyComplete();

        repository.findById(110L).as(StepVerifier::create)
            .expectNext(new AvailabilityAlertEntity(110L, "light", "Main", "tobi", NOON, NOON.plusHours(1)))
            .verifyComplete();
    }

    @Test
    void should_refuse_a_kind_it_does_not_know() {
        entityTemplate.insert(new AvailabilityAlertEntity(110L, "lamp", "Main", "tobi", NOON, NOON))
            .as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }
}
