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
 * The availability monitor (HAS-264); a bare number is minutes.
 *
 * @param offlineAfter        how long a Shelly light is unreachable before the household hears of it: a Gen1
 *                            light is polled every minute, so at least two failed polls
 * @param reminderEvery       how often an outage that goes on is repeated
 * @param sensorSilentAfter   how long a presence sensor may stay silent - it publishes its zone about once a minute
 * @param batterySilentAfter  how long a battery motion sensor may stay silent - it sends only when it senses something
 */
@Validated
@ConfigurationProperties(prefix = "light.monitor")
public record MonitorProperties(
    @NotNull @DurationMin(minutes = 2) @DurationMax(hours = 2) @DurationUnit(ChronoUnit.MINUTES)
    Duration offlineAfter,
    @NotNull @DurationMin(minutes = 15) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    Duration reminderEvery,
    @NotNull @DurationMin(minutes = 10) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    Duration sensorSilentAfter,
    @NotNull @DurationMin(hours = 1) @DurationMax(days = 7) @DurationUnit(ChronoUnit.MINUTES)
    Duration batterySilentAfter
) {
}
