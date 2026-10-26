package cloud.cholewa.light.occupancy;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.config.OccupancyProperties;
import cloud.cholewa.light.model.RoomOccupancy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

//What the sensors of the occupancy said (HAS-260), in memory, and what a room makes of it. Nothing is
//stored: after a start every room is unknown - never empty - until its sensors speak, and no rule may act
//on an unknown room.
//- occupied: the presence sensors of the room. Occupied when one says somebody is there, empty when every
//  one says nobody is, unknown otherwise; a presence sensor that left the broker is not known.
//- lastMotionAt: the newest motion of any motion sensor of the room.
//- motion / motionClearedAt (HAS-349): a BLU Motion sends the start of a motion and its clear, so its true
//  is a motion still going on; the room has one while any of its BLU Motions does, and the newest clear.
//  A true older than light.occupancy.motion-held-at-most counts as cleared then (a lost clear packet).
//- illumination / lux: the newest of any sensor of the room that reports it - a word of a Presence Gen4,
//  a number of a BLU Motion or a Shelly Motion.
//- since: when the service saw the occupancy of the room change, from unknown included.
//- reachable: a Presence by the broker (online), a BLU Motion while a gateway confirmed it within
//  light.occupancy.gateway-silent-after; a sleeping Shelly Motion and a PIR of the AMX system cannot tell
@Slf4j
@Component
public class OccupancyTracker {

    private final OccupancyRegistry occupancyRegistry;
    private final OccupancyProperties properties;
    private final Clock clock;

    private final Map<Long, Reading> readings = new ConcurrentHashMap<>();
    private final Map<RoomName, Since> rooms = new ConcurrentHashMap<>();

    public OccupancyTracker(final OccupancyRegistry occupancyRegistry, final OccupancyProperties properties, final Clock clock) {
        this.occupancyRegistry = occupancyRegistry;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * What one sensor said. {@code motionHeardAt} / {@code luxHeardAt}: when a gateway heard a BLU sensor -
     * of two reports the one heard later counts; {@code confirmedAt}: when a gateway last stood for it.
     */
    record Reading(
        Boolean occupied,
        Boolean online,
        String illumination,
        LocalDateTime illuminationAt,
        Double lux,
        LocalDateTime luxAt,
        LocalDateTime lastMotionAt,
        Instant motionHeardAt,
        Instant luxHeardAt,
        LocalDateTime lastReportAt,
        LocalDateTime confirmedAt,
        Boolean motionNow,
        LocalDateTime motionClearedAt
    ) {
        static final Reading NONE = new Reading(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private record Since(Boolean occupied, LocalDateTime at) {
    }

    /**
     * A Presence said whether somebody is in its zone. Published, it is on the broker; read over HTTP
     * ({@code published} false) it says nothing of the broker - MQTT owns whether it is reachable, as
     * for the lights.
     */
    public void presence(final OccupancySensor sensor, final boolean occupied, final boolean published) {
        final LocalDateTime now = now();
        readings.compute(sensor.id(), (id, held) -> {
            final Reading old = held == null ? Reading.NONE : held;
            if (!Boolean.valueOf(occupied).equals(old.occupied())) {
                log.info("Presence sensor {} ({}) in the {}: {}", sensor.id(), sensor.name(), sensor.room().getValue(),
                    occupied ? "somebody is there" : "nobody is there");
            }
            return new Reading(occupied, published ? Boolean.TRUE : old.online(), old.illumination(), old.illuminationAt(),
                old.lux(), old.luxAt(),
                old.lastMotionAt(), old.motionHeardAt(), old.luxHeardAt(), now, old.confirmedAt(), old.motionNow(),
                old.motionClearedAt());
        });
        settle(sensor.room(), now);
    }

    /** A Presence joined or left the broker - the last will of a device is {@code online: false}. */
    public void online(final OccupancySensor sensor, final boolean online) {
        final LocalDateTime now = now();
        readings.compute(sensor.id(), (id, held) -> {
            final Reading old = held == null ? Reading.NONE : held;
            if (!Boolean.valueOf(online).equals(old.online())) {
                log.info("Presence sensor {} ({}) {} the broker", sensor.id(), sensor.name(), online ? "joined" : "left");
            }
            return new Reading(old.occupied(), online, old.illumination(), old.illuminationAt(), old.lux(), old.luxAt(),
                old.lastMotionAt(), old.motionHeardAt(), old.luxHeardAt(), online ? now : old.lastReportAt(), old.confirmedAt(),
                old.motionNow(), old.motionClearedAt());
        });
        settle(sensor.room(), now);
    }

    /** The light a sensor reports: a word (Presence), a number (lux), or both; what it does not say is kept. */
    public void illumination(final OccupancySensor sensor, final String word, final Double lux) {
        final LocalDateTime now = now();
        readings.compute(sensor.id(), (id, held) -> {
            final Reading old = held == null ? Reading.NONE : held;
            return new Reading(old.occupied(), old.online(),
                word == null ? old.illumination() : word, word == null ? old.illuminationAt() : now,
                lux == null ? old.lux() : lux, lux == null ? old.luxAt() : now,
                old.lastMotionAt(), old.motionHeardAt(), old.luxHeardAt(), now, old.confirmedAt(), old.motionNow(),
                old.motionClearedAt());
        });
    }

    /**
     * A motion sensor that reports at once - a Shelly Motion by its action, a PIR of the AMX system.
     *
     * @return whether it was a motion - for the rules of the room (HAS-262)
     */
    public boolean motion(final OccupancySensor sensor, final boolean motion) {
        final LocalDateTime now = now();
        readings.compute(sensor.id(), (id, held) -> {
            final Reading old = held == null ? Reading.NONE : held;
            if (motion) {
                log.debug("Motion sensor {} ({}) in the {}: motion", sensor.id(), sensor.name(), sensor.room().getValue());
            }
            return new Reading(old.occupied(), old.online(), old.illumination(), old.illuminationAt(), old.lux(), old.luxAt(),
                motion ? now : old.lastMotionAt(), old.motionHeardAt(), old.luxHeardAt(), now, old.confirmedAt(),
                old.motionNow(), old.motionClearedAt());
        });
        return motion;
    }

    /**
     * What a gateway said of a BLU Motion, heard at {@code heardAt} (the {@code last_updated_ts} of its
     * component; null when the gateway did not say). A report heard earlier than one already taken changes
     * nothing - a read of a gateway may bring an older state than a publish a moment before it - but the
     * gateway stood for the sensor all the same.
     *
     * @return whether a motion was taken - for the rules of the room (HAS-262), which judge how fresh it is
     */
    public boolean bluMotion(final OccupancySensor sensor, final Boolean motion, final Instant heardAt) {
        final LocalDateTime now = now();
        final AtomicBoolean taken = new AtomicBoolean();
        readings.compute(sensor.id(), (id, held) -> {
            final Reading old = held == null ? Reading.NONE : held;
            final boolean newer = heardAt == null || old.motionHeardAt() == null || heardAt.isAfter(old.motionHeardAt());
            if (motion == null || !newer) {
                return confirmed(old, now);
            }
            taken.set(motion);
            final LocalDateTime at = heardAt == null ? now : at(heardAt);
            //HAS-349: a BLU Motion sends the start of a motion and its clear - true is a motion still going on
            return new Reading(old.occupied(), old.online(), old.illumination(), old.illuminationAt(), old.lux(), old.luxAt(),
                motion ? at : old.lastMotionAt(), heardAt == null ? old.motionHeardAt() : heardAt, old.luxHeardAt(),
                now, now, motion, motion ? old.motionClearedAt() : at);
        });
        return taken.get();
    }

    /** The illuminance a gateway heard of a BLU Motion, by the same rule of the later report. */
    public void bluLux(final OccupancySensor sensor, final Double lux, final Instant heardAt) {
        final LocalDateTime now = now();
        readings.compute(sensor.id(), (id, held) -> {
            final Reading old = held == null ? Reading.NONE : held;
            final boolean newer = heardAt == null || old.luxHeardAt() == null || heardAt.isAfter(old.luxHeardAt());
            if (lux == null || !newer) {
                return confirmed(old, now);
            }
            return new Reading(old.occupied(), old.online(), old.illumination(), old.illuminationAt(),
                lux, heardAt == null ? now : at(heardAt), old.lastMotionAt(), old.motionHeardAt(),
                heardAt == null ? old.luxHeardAt() : heardAt, now, now, old.motionNow(), old.motionClearedAt());
        });
    }

    /** A gateway answered and stands for a BLU sensor it holds, without a report of its own. */
    public void confirm(final OccupancySensor sensor) {
        final LocalDateTime now = now();
        readings.compute(sensor.id(), (id, held) -> confirmed(held == null ? Reading.NONE : held, now));
    }

    //a confirmation is not a report: lastReportAt stays what the sensor last said
    private static Reading confirmed(final Reading old, final LocalDateTime now) {
        return new Reading(old.occupied(), old.online(), old.illumination(), old.illuminationAt(), old.lux(), old.luxAt(),
            old.lastMotionAt(), old.motionHeardAt(), old.luxHeardAt(), old.lastReportAt(), now, old.motionNow(),
            old.motionClearedAt());
    }

    /** The occupancy of a room by its sensors as last read; a room without one has an empty list. */
    public RoomOccupancy room(final RoomName room) {
        final LocalDateTime now = now();
        final List<OccupancySensor> sensors = sensorsOf(room);
        final Since since = settle(room, occupied(sensors), now);

        final List<Reading> known = sensors.stream().map(sensor -> readings.get(sensor.id())).filter(Objects::nonNull).toList();
        final LocalDateTime lastMotionAt = known.stream().map(Reading::lastMotionAt).filter(Objects::nonNull)
            .max(Comparator.naturalOrder()).orElse(null);
        final Reading lit = known.stream().filter(reading -> reading.illuminationAt() != null)
            .max(Comparator.comparing(Reading::illuminationAt)).orElse(null);
        final Reading measured = known.stream().filter(reading -> reading.luxAt() != null)
            .max(Comparator.comparing(Reading::luxAt)).orElse(null);
        final List<Motion> motions = known.stream().map(reading -> motionOf(reading, now)).filter(Objects::nonNull).toList();

        return new RoomOccupancy(
            room.getValue(),
            since.occupied(),
            since.occupied() == null ? null : since.at(),
            lastMotionAt,
            motions.isEmpty() ? null : motions.stream().anyMatch(Motion::going),
            motions.stream().map(Motion::clearedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null),
            lit == null ? null : lit.illumination(),
            measured == null ? null : measured.lux(),
            sensors.stream().map(sensor -> view(sensor, readings.get(sensor.id()), now)).toList());
    }

    //Occupied when one says somebody is there, empty when every one says nobody is; a room without a
    //presence sensor, or with one that is not known, is not known
    private Boolean occupied(final List<OccupancySensor> sensors) {
        final List<Boolean> said = sensors.stream()
            .filter(sensor -> sensor.kind() == SensorKind.PRESENCE)
            .map(sensor -> occupiedOf(readings.get(sensor.id())))
            .toList();
        if (said.isEmpty()) {
            return null;
        }
        if (said.contains(Boolean.TRUE)) {
            return true;
        }
        return said.contains(null) ? null : false;
    }

    //HAS-349: what a BLU Motion says of a motion now - null when it never said. A motion held past
    //motion-held-at-most counts as ended at its start plus the bound: a lost clear keeps no room occupied
    private Motion motionOf(final Reading reading, final LocalDateTime now) {
        if (reading.motionNow() == null) {
            return null;
        }
        if (!reading.motionNow()) {
            return new Motion(false, reading.motionClearedAt());
        }
        final LocalDateTime heldUntil = reading.lastMotionAt().plus(properties.motionHeldAtMost());
        return heldUntil.isAfter(now) ? new Motion(true, reading.motionClearedAt()) : new Motion(false, heldUntil);
    }

    private record Motion(boolean going, LocalDateTime clearedAt) {
    }

    private static Boolean occupiedOf(final Reading reading) {
        return reading == null || Boolean.FALSE.equals(reading.online()) ? null : reading.occupied();
    }

    private void settle(final RoomName room, final LocalDateTime now) {
        settle(room, occupied(sensorsOf(room)), now);
    }

    //the time the room was last seen to change, an unknown room included
    private Since settle(final RoomName room, final Boolean occupied, final LocalDateTime now) {
        return rooms.compute(room, (name, held) ->
            held != null && Objects.equals(held.occupied(), occupied) ? held : new Since(occupied, now));
    }

    private RoomOccupancy.SensorView view(final OccupancySensor sensor, final Reading reading, final LocalDateTime now) {
        return new RoomOccupancy.SensorView(sensor.id(), sensor.name(), sensor.kind().value(),
            reading == null ? null : reading.lastReportAt(),
            reachable(sensor, reading, now));
    }

    private Boolean reachable(final OccupancySensor sensor, final Reading reading, final LocalDateTime now) {
        if (reading == null) {
            return null;
        }
        return switch (sensor.binding()) {
            case SensorBinding.Presence presence -> reading.online();
            case SensorBinding.BluMotion blu -> reading.confirmedAt() == null
                ? null
                : !reading.confirmedAt().isBefore(now.minus(properties.gatewaySilentAfter()));
            case SensorBinding.Gen1Motion gen1 -> null;
            case SensorBinding.EatonMotion eaton -> null;
        };
    }

    private List<OccupancySensor> sensorsOf(final RoomName room) {
        return occupancyRegistry.sensors().stream().filter(sensor -> sensor.room() == room).toList();
    }

    private LocalDateTime at(final Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone()).truncatedTo(ChronoUnit.SECONDS);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
