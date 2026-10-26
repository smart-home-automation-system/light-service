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
 * The sensors of the occupancy of the rooms (HAS-260).
 *
 * @param gatewaySilentAfter how long a Bluetooth motion sensor counts as reachable without its gateway
 *                           confirming it - by publishing the sensor or by answering the read of every
 *                           five minutes; a bare number is minutes. A BLU sensor sends when it senses
 *                           something, so its own silence proves nothing; the gateway that stopped
 *                           answering does (the rule of the window sensors of blinds-service). At
 *                           least two reads of the gateway
 * @param motionHeldAtMost   how long a BLU Motion that reported a motion and never its end counts as
 *                           still moving (HAS-349); a bare number is minutes. A BLU Motion sends a packet
 *                           when a motion starts and another when it clears; a lost clear must not keep a
 *                           room occupied for good, so a motion older than this counts as ended at its
 *                           start plus this bound
 */
@Validated
@ConfigurationProperties(prefix = "light.occupancy")
public record OccupancyProperties(
    @NotNull @DurationMin(minutes = 10) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    Duration gatewaySilentAfter,
    @NotNull @DurationMin(minutes = 5) @DurationMax(hours = 12) @DurationUnit(ChronoUnit.MINUTES)
    Duration motionHeldAtMost
) {
}
