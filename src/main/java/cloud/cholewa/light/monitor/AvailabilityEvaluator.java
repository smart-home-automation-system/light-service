package cloud.cholewa.light.monitor;

import cloud.cholewa.light.config.MonitorProperties;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

//What the availability monitor makes of the devices it watches and the alerts already open (HAS-264): pure,
//pinned by AvailabilityEvaluatorTest.
//- a Shelly light is out once it has been unreachable for offlineAfter, since it became unreachable;
//- a presence sensor is out once it has been silent for sensorSilentAfter, a battery sensor for
//  batterySilentAfter, since it was last heard;
//- out and not announced: an alert; announced and still out: a reminder every reminderEvery; announced and
//  back: an info; announced and no longer watched (removed from the registry): forgotten without a word.
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AvailabilityEvaluator {

    /** What is watched. */
    public enum Kind {
        LIGHT("light"), PRESENCE_SENSOR("presence sensor"), BATTERY_SENSOR("battery sensor");

        private final String value;

        Kind(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /**
     * A watched device: a light with when it became unreachable (null while it is reachable or not known), a
     * sensor with when it was last heard.
     */
    public record Watched(long id, String name, String room, Kind kind, LocalDateTime since) {
    }

    /** An alert the household had. */
    public record Alert(long id, Kind kind, String name, String room, LocalDateTime since, LocalDateTime lastAlertAt) {
    }

    /** What is due: new alerts, reminders, returns, and the alerts of devices no longer watched. */
    public record Decision(List<Alert> alerts, List<Alert> reminders, List<Alert> returns, List<Alert> forgotten) {

        public boolean nothing() {
            return alerts.isEmpty() && reminders.isEmpty() && returns.isEmpty() && forgotten.isEmpty();
        }
    }

    public static Decision decide(final List<Watched> watched, final Map<Long, Alert> open, final LocalDateTime now,
                                  final MonitorProperties properties) {
        final List<Alert> alerts = new ArrayList<>();
        final List<Alert> reminders = new ArrayList<>();
        final List<Alert> returns = new ArrayList<>();

        for (final Watched device : watched) {
            final boolean out = out(device, now, properties);
            final Alert alert = open.get(device.id());
            if (out && alert == null) {
                alerts.add(new Alert(device.id(), device.kind(), device.name(), device.room(), device.since(), now));
            } else if (out && !alert.lastAlertAt().plus(properties.reminderEvery()).isAfter(now)) {
                reminders.add(alert);
            } else if (!out && alert != null) {
                returns.add(alert);
            }
        }
        final Set<Long> ids = watched.stream().map(Watched::id).collect(Collectors.toSet());
        final List<Alert> forgotten = open.values().stream().filter(alert -> !ids.contains(alert.id())).toList();
        return new Decision(alerts, reminders, returns, forgotten);
    }

    private static boolean out(final Watched device, final LocalDateTime now, final MonitorProperties properties) {
        if (device.since() == null) {
            return false;
        }
        final Duration limit = switch (device.kind()) {
            case LIGHT -> properties.offlineAfter();
            case PRESENCE_SENSOR -> properties.sensorSilentAfter();
            case BATTERY_SENSOR -> properties.batterySilentAfter();
        };
        return !device.since().plus(limit).isAfter(now);
    }
}
