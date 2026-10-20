package cloud.cholewa.light.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * The Eaton lights (HAS-295).
 *
 * @param confirmAfter how long the status of an Eaton actuator is waited for after a command before it
 *                     is asked for it, and then how long its answer is waited for; a bare number is
 *                     seconds. An actuator reports within a second or two - when its status gets
 *                     through. 1 to 60 s: the window of a command is two minutes
 */
@Validated
@ConfigurationProperties(prefix = "light.eaton")
public record EatonProperties(
    @NotNull @DurationMin(seconds = 1) @DurationMax(seconds = 60) @DurationUnit(ChronoUnit.SECONDS)
    Duration confirmAfter
) {
}
