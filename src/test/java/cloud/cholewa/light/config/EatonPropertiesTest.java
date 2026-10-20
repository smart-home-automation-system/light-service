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

//application.yaml as the profiles read it (HAS-295)
class EatonPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_ask_an_eaton_light_for_its_status_five_seconds_after_the_command() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(EatonProperties.class).confirmAfter())
                .isEqualTo(Duration.ofSeconds(5)));
    }

    //without the unit a bare number would bind as milliseconds: the request would go out before the
    //actuator could report the change
    @Test
    void should_read_a_bare_number_as_seconds() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "light.eaton.confirm-after=8")
            .run(context -> assertThat(context.getBean(EatonProperties.class).confirmAfter())
                .isEqualTo(Duration.ofSeconds(8)));
    }

    //the request and the wait for its answer have to end well inside the two minutes of a command
    @ParameterizedTest
    @ValueSource(strings = {
        "light.eaton.confirm-after=0",
        "light.eaton.confirm-after=500ms",
        "light.eaton.confirm-after=5000",
        "light.eaton.confirm-after=61s",
        "light.eaton.confirm-after="
    })
    void should_not_start_with_a_wait_out_of_bounds(final String confirmAfter) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", confirmAfter)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(EatonProperties.class)
    static class Properties {
    }
}
