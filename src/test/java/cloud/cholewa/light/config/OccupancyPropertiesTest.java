package cloud.cholewa.light.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//application.yaml as the profiles read it (HAS-260)
class OccupancyPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_hold_a_blu_motion_reachable_for_half_an_hour_without_its_gateway() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(OccupancyProperties.class).gatewaySilentAfter())
                .isEqualTo(Duration.ofMinutes(30)));
    }

    //without the unit a bare number would bind as milliseconds: every BLU Motion unreachable at once
    @Test
    void should_read_a_bare_number_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "light.occupancy.gateway-silent-after=45")
            .run(context -> assertThat(context.getBean(OccupancyProperties.class).gatewaySilentAfter())
                .isEqualTo(Duration.ofMinutes(45)));
    }

    //the gateways are read every five minutes: less than two reads, or more than a day, is no bound
    @ParameterizedTest
    @ValueSource(strings = {
        "light.occupancy.gateway-silent-after=0",
        "light.occupancy.gateway-silent-after=9",
        "light.occupancy.gateway-silent-after=PT9M59S",
        "light.occupancy.gateway-silent-after=PT24H1M",
        "light.occupancy.gateway-silent-after="
    })
    void should_not_start_with_a_bound_out_of_its_range(final String silentAfter) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", silentAfter)
            .run(context -> assertThat(context).hasFailed());
    }

    //HAS-349: a motion never cleared counts as going on for an hour at most, a bare number is minutes
    @Test
    void should_hold_a_motion_never_cleared_for_an_hour_at_most() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(OccupancyProperties.class).motionHeldAtMost())
                .isEqualTo(Duration.ofMinutes(60)));
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "light.occupancy.motion-held-at-most=90")
            .run(context -> assertThat(context.getBean(OccupancyProperties.class).motionHeldAtMost())
                .isEqualTo(Duration.ofMinutes(90)));
    }

    //less than five minutes would cut a motion short; more than twelve hours would keep a lost clear all night
    @ParameterizedTest
    @ValueSource(strings = {
        "light.occupancy.motion-held-at-most=0",
        "light.occupancy.motion-held-at-most=4",
        "light.occupancy.motion-held-at-most=PT12H1M",
        "light.occupancy.motion-held-at-most="
    })
    void should_not_start_with_a_motion_bound_out_of_its_range(final String heldAtMost) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", heldAtMost)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(OccupancyProperties.class)
    static class Properties {
    }
}
