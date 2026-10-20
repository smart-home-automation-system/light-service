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

//application.yaml as the profiles read it (HAS-264)
class MonitorPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_watch_with_the_limits_of_the_task() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(MonitorProperties.class)).isEqualTo(new MonitorProperties(
                Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofMinutes(30), Duration.ofHours(24))));
    }

    //without the unit a bare number would bind as milliseconds: every light out after a failed poll
    @Test
    void should_read_a_bare_number_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "light.monitor.offline-after=10")
            .run(context -> assertThat(context.getBean(MonitorProperties.class).offlineAfter()).isEqualTo(Duration.ofMinutes(10)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "light.monitor.offline-after=1",
        "light.monitor.offline-after=PT2H1M",
        "light.monitor.reminder-every=14",
        "light.monitor.sensor-silent-after=9",
        "light.monitor.battery-silent-after=59",
        "light.monitor.battery-silent-after=PT169H",
        "light.monitor.offline-after="
    })
    void should_not_start_with_a_limit_out_of_its_range(final String value) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(MonitorProperties.class)
    static class Properties {
    }
}
