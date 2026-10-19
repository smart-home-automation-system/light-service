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
 * The empty house (HAS-263).
 *
 * @param enabled whether the service acts on an empty house at all - the lights left on by day, the presence
 *                simulation by night; the empty house is watched and answered either way
 * @param after   how long the house is empty before the service acts; a bare number is minutes. The
 *                presence of a member is read every minute, and one who steps out to the bin for a moment
 *                must not turn the lights off behind them
 */
@Validated
@ConfigurationProperties(prefix = "light.empty-house")
public record EmptyHouseProperties(
    @NotNull Boolean enabled,
    @NotNull @DurationMin(minutes = 1) @DurationMax(hours = 2) @DurationUnit(ChronoUnit.MINUTES)
    Duration after
) {
}
