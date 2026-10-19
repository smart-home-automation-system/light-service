package cloud.cholewa.light.emptyhouse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

//Whether the house is empty (HAS-263), in the memory of the one instance; held apart from EmptyHouseEngine so
//that the rules engine can ask it without depending on what acts on it. Unknown after a start - never empty.
//- empty: every active member of the household is away, each by a reading of presence-service newer than
//  FRESH; a member presence-service has never observed is not away; no members at all is not known.
//- occupied: one member is at home. Only that ends an empty house: a presence-service that cannot be read
//  for a while suspends what the service does (unknown) but keeps since, so a short outage neither restarts
//  the delay nor announces the empty house twice.
@Slf4j
@Component
public class HouseState {

    //presence-service checks the household every minute: a reading older than this is not a reading
    static final Duration FRESH = Duration.ofMinutes(5);

    private volatile HouseOccupancy occupancy = HouseOccupancy.UNKNOWN;
    private volatile LocalDateTime emptySince;
    private volatile LocalDateTime actingFrom;

    /** What the residents say of the house at a time; the residents of a call that failed are null. */
    public static HouseOccupancy judge(final List<ResidentPresence> residents, final LocalDateTime now) {
        if (residents == null || residents.isEmpty()) {
            return HouseOccupancy.UNKNOWN;
        }
        if (residents.stream().anyMatch(resident -> Boolean.TRUE.equals(resident.present()))) {
            return HouseOccupancy.OCCUPIED;
        }
        final boolean everyAwayAndFresh = residents.stream().allMatch(resident -> Boolean.FALSE.equals(resident.present())
            && resident.lastCheckedAt() != null && !resident.lastCheckedAt().isBefore(now.minus(FRESH)));
        return everyAwayAndFresh ? HouseOccupancy.EMPTY : HouseOccupancy.UNKNOWN;
    }

    /**
     * Takes what presence-service said now.
     *
     * @return whether somebody came back to a house that was empty - the end of an empty house
     */
    public synchronized boolean update(final HouseOccupancy read, final LocalDateTime now, final Duration after) {
        final HouseOccupancy before = occupancy;
        occupancy = read;
        if (read != before) {
            if (read == HouseOccupancy.UNKNOWN) {
                log.warn("Whether anybody is at home is not known now (was {})", before.value());
            } else {
                log.info("The house is {} (was {})", read.value(), before.value());
            }
        }
        if (read == HouseOccupancy.EMPTY && emptySince == null) {
            emptySince = now.truncatedTo(ChronoUnit.SECONDS);
        }
        if (emptySince != null) {
            actingFrom = emptySince.plus(after);
        }
        if (read == HouseOccupancy.OCCUPIED && emptySince != null) {
            log.info("Somebody came back home; the house was empty since {}", emptySince);
            emptySince = null;
            actingFrom = null;
            return true;
        }
        return false;
    }

    public HouseOccupancy occupancy() {
        return occupancy;
    }

    /** Since when the house is empty, kept through an unknown spell; null when it is not. */
    public LocalDateTime emptySince() {
        return emptySince;
    }

    /** Whether the house is empty, known to be now, for the delay: the service acts on it and the rules pause. */
    public boolean active(final LocalDateTime now) {
        return occupancy == HouseOccupancy.EMPTY && actingFrom != null && !now.isBefore(actingFrom);
    }

    /** When the service starts acting on the empty house; null while it is not empty. */
    public LocalDateTime actingFrom() {
        return actingFrom;
    }
}
