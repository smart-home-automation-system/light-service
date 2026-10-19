package cloud.cholewa.light.model.emptyhouse;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalTime;
import java.util.List;

/**
 * One phase of the presence simulation (HAS-263), written whole - nothing has a default.<br>
 * {@code from}, {@code to} - {@code HH:mm}; a time after noon is the evening the night starts, before noon
 * the morning after. The phase runs only at night: from the dusk at the earliest, until the sunrise at the
 * latest. Each bound moves by up to {@code jitterMinutes} (0-60) each night, at random.<br>
 * {@code minStayMinutes}, {@code maxStayMinutes} - how long somebody stays in a spot (1-180).<br>
 * {@code maxSpotsAtOnce} - 1 to 3 stays lit at the same time.<br>
 * {@code visitsMin}, {@code visitsMax} - left out: the phase is continuous, somebody is always somewhere,
 * moving from stay to stay; given (1-6): the phase is that many short visits (a way to the bathroom), the
 * rest of the time dark.<br>
 * {@code spots} - where; at least one {@code stay}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PhasePlan(
    PhaseName phase,
    Boolean enabled,
    @JsonFormat(pattern = PhasePlan.MINUTES) LocalTime from,
    @JsonFormat(pattern = PhasePlan.MINUTES) LocalTime to,
    Integer jitterMinutes,
    Integer minStayMinutes,
    Integer maxStayMinutes,
    Integer maxSpotsAtOnce,
    Integer visitsMin,
    Integer visitsMax,
    List<Spot> spots
) {

    static final String MINUTES = "HH:mm";

    public boolean visits() {
        return visitsMax != null;
    }
}
