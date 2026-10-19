package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.light.config.LocationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.shredzone.commons.suncalc.SunTimes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

//The times of the sun at the house, worked out once a day - the first time the day is asked for - and kept
//for a few days around it. A copy of the SunCalendar of blinds-service (the second copy, below the three of
//the rule of cloud.cholewa-commons) with the civil dusk instead of the sunset: the presence simulation
//starts when the windows are dark, not when the sun touches the horizon. Only the times within the day
//itself count (oneDay)
@Slf4j
@Component
@RequiredArgsConstructor
public class SunCalendar {

    private final LocationProperties location;
    private final Clock clock;

    private final Map<LocalDate, SunDay> cached = new ConcurrentHashMap<>();

    public SunDay of(final LocalDate date) {
        final SunDay current = cached.get(date);
        if (current != null) {
            return current;
        }

        final SunDay computed = new SunDay(date, rise(date), dusk(date));
        cached.keySet().removeIf(kept -> kept.isBefore(date.minusDays(2)) || kept.isAfter(date.plusDays(2)));
        cached.put(date, computed);
        log.info("Sun on {} at the house: sunrise {}, dusk {}", date, computed.sunrise(), computed.dusk());
        return computed;
    }

    private LocalTime rise(final LocalDate date) {
        return toMinute(times(date, SunTimes.Twilight.VISUAL).getRise(), date);
    }

    private LocalTime dusk(final LocalDate date) {
        return toMinute(times(date, SunTimes.Twilight.CIVIL).getSet(), date);
    }

    private SunTimes times(final LocalDate date, final SunTimes.Twilight twilight) {
        return SunTimes.compute()
            .timezone(clock.getZone())
            .on(date)
            .at(location.latitude(), location.longitude())
            .twilight(twilight)
            .oneDay()
            .execute();
    }

    //rounded to the nearest minute; a time of another day is none of this one
    private static LocalTime toMinute(final ZonedDateTime time, final LocalDate date) {
        if (time == null) {
            return null;
        }
        final ZonedDateTime rounded = time.plusSeconds(30).truncatedTo(ChronoUnit.MINUTES);
        return rounded.toLocalDate().equals(date) ? rounded.toLocalTime() : null;
    }
}
