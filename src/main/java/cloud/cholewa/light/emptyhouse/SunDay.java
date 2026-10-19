package cloud.cholewa.light.emptyhouse;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * The sunrise and the civil dusk of a day at the house, local time to the minute; {@code null} for one the
 * day does not have (never in Poland, but the calculation can say so).
 */
public record SunDay(LocalDate date, LocalTime sunrise, LocalTime dusk) {
}
