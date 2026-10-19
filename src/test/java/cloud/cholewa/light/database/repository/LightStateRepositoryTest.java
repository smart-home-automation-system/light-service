package cloud.cholewa.light.database.repository;

import cloud.cholewa.light.database.model.LightStateEntity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//The SQL of the state is PostgreSQL's own (DISTINCT ON), so it runs against a PostgreSQL in Docker
//(Testcontainers), on the table the migration of the service creates. The slice builds a plain
//connection from spring.r2dbc.*, not the pooled one of cholewa-commons, which is why the container
//needs no SSL here. Without a Docker the test fails, it is not skipped
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class LightStateRepositoryTest {

    private static final LocalDateTime EVENING = LocalDateTime.of(2026, 10, 19, 19, 0);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private LightStateRepository sut;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    //the test profile switches Flyway off and the slice would not run it anyway
    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void forgetTheStates() {
        sut.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    //the latest is the highest id, not the latest start: the night the clocks go back a later row may
    //start earlier
    @Test
    void should_read_the_latest_row_of_every_light() {
        save(89, false, 100, "0,255,0", EVENING);
        save(35, true, null, null, EVENING);
        save(89, true, 60, "255,0,0", EVENING.plusMinutes(5));
        save(89, false, 60, "255,0,0", EVENING.minusHours(1));

        sut.findLatestPerDevice().collectList().as(StepVerifier::create)
            .assertNext(rows -> {
                assertThat(rows).extracting(LightStateEntity::deviceId).containsExactly(35L, 89L);
                assertThat(rows.get(0).brightness()).isNull();
                assertThat(rows.get(0).rgb()).isNull();
                assertThat(rows.get(1).switchedOn()).isFalse();
                assertThat(rows.get(1).rgb()).isEqualTo("255,0,0");
            })
            .verifyComplete();
    }

    //a confirmation moves the latest row of the light only, and writes what is known now
    @Test
    void should_confirm_the_latest_row_of_the_light() {
        save(90, false, 100, null, EVENING);
        save(90, true, null, null, EVENING.plusMinutes(1));
        save(88, true, 40, null, EVENING);

        sut.touchLatest(90, EVENING.plusMinutes(2), 30, null, null).as(StepVerifier::create)
            .expectNext(1)
            .verifyComplete();

        sut.findLatestPerDevice().collectList().as(StepVerifier::create)
            .assertNext(rows -> {
                final LightStateEntity wallLeft = rows.stream().filter(row -> row.deviceId() == 90).findFirst().orElseThrow();
                assertThat(wallLeft.brightness()).isEqualTo(30);
                assertThat(wallLeft.lastCheckedAt()).isEqualTo(EVENING.plusMinutes(2));
                assertThat(wallLeft.startedAt()).isEqualTo(EVENING.plusMinutes(1));
                final LightStateEntity dimmer = rows.stream().filter(row -> row.deviceId() == 88).findFirst().orElseThrow();
                assertThat(dimmer.lastCheckedAt()).isEqualTo(EVENING);
            })
            .verifyComplete();
    }

    @Test
    void should_confirm_nothing_for_a_light_without_a_row() {
        sut.touchLatest(42, EVENING, null, null, null).as(StepVerifier::create)
            .expectNext(0)
            .verifyComplete();
    }

    //the table keeps only what a light can report
    @Test
    void should_refuse_a_brightness_or_a_color_out_of_range() {
        sut.save(entity(87, true, 101, null, null, EVENING)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
        sut.save(entity(87, true, 50, "255,0", null, EVENING)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
        sut.save(entity(87, true, 50, null, 256, EVENING)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    private void save(final long device, final boolean on, final Integer brightness, final String rgb,
                      final LocalDateTime startedAt) {
        sut.save(entity(device, on, brightness, rgb, null, startedAt)).as(StepVerifier::create)
            .expectNextCount(1)
            .verifyComplete();
    }

    private static LightStateEntity entity(final long device, final boolean on, final Integer brightness,
                                           final String rgb, final Integer white, final LocalDateTime startedAt) {
        return new LightStateEntity(null, device, on, brightness, rgb, white, "dashboard", startedAt, startedAt);
    }
}
