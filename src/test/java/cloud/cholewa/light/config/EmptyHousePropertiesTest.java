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

//application.yaml as the profiles read it (HAS-263)
class EmptyHousePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_act_on_an_empty_house_after_ten_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> {
                assertThat(context.getBean(EmptyHouseProperties.class).enabled()).isTrue();
                assertThat(context.getBean(EmptyHouseProperties.class).after()).isEqualTo(Duration.ofMinutes(10));
            });
    }

    //without the unit a bare number would bind as milliseconds: the lights off the moment the last one is out
    @Test
    void should_read_a_bare_number_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "light.empty-house.after=2")
            .run(context -> assertThat(context.getBean(EmptyHouseProperties.class).after()).isEqualTo(Duration.ofMinutes(2)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "light.empty-house.after=0",
        "light.empty-house.after=PT59S",
        "light.empty-house.after=PT2H1M",
        "light.empty-house.after=",
        "light.empty-house.enabled="
    })
    void should_not_start_with_a_value_out_of_its_range(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(EmptyHouseProperties.class)
    static class Properties {
    }
}
