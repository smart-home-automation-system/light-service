package cloud.cholewa.light.monitor;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.config.MonitorProperties;
import cloud.cholewa.light.database.model.AvailabilityAlertEntity;
import cloud.cholewa.light.database.repository.AvailabilityAlertRepository;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.model.RoomOccupancy;
import cloud.cholewa.light.monitor.AvailabilityEvaluator.Alert;
import cloud.cholewa.light.monitor.AvailabilityEvaluator.Decision;
import cloud.cholewa.light.monitor.AvailabilityEvaluator.Kind;
import cloud.cholewa.light.monitor.AvailabilityEvaluator.Watched;
import cloud.cholewa.light.occupancy.OccupancyRegistry;
import cloud.cholewa.light.occupancy.OccupancySensor;
import cloud.cholewa.light.occupancy.OccupancyTracker;
import cloud.cholewa.light.occupancy.SensorBinding;
import cloud.cholewa.light.occupancy.SensorKind;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.light.state.LightStateTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//The availability monitor (HAS-264): every minute the Shelly lights and the sensors of the occupancy are
//judged by AvailabilityEvaluator against the alerts stored in light_availability_alert, and what is due goes to
//the household through NotificationPublisher - one alert, one reminder and one info a pass at most, each naming
//every device of its kind, so that a Wi-Fi outage is one message and not thirty.
//- A Shelly light is unreachable as LightStateTracker says (a Gen1 light by a failed poll of /status, a Gen2
//  light by its MQTT last will); a reachability not known (after a start) is not judged. The Eaton lights are not
//  watched - amx-service and the frame server have their own health.
//- A sensor is heard when it reports (OccupancyTracker); one never heard since the start counts from the start.
//  The PIRs of the AMX system are not watched: they report only after dark.
//- The table changes only once the broker has taken the message; a notification that fails is tried again with
//  the next pass, and neither fails nor stalls the pass for longer than PUBLISH_TIMEOUT
@Slf4j
@Component
public class AvailabilityMonitor {

    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final LightRegistry lightRegistry;
    private final LightStateTracker lightStateTracker;
    private final OccupancyRegistry occupancyRegistry;
    private final OccupancyTracker occupancyTracker;
    private final AvailabilityAlertRepository alertRepository;
    private final R2dbcEntityTemplate entityTemplate;
    private final NotificationPublisher notificationPublisher;
    private final MonitorProperties properties;
    private final Clock clock;

    //when each light was first seen unreachable, in memory: a restart counts anew, the table keeps the alert
    private final Map<Long, LocalDateTime> unreachableSince = new ConcurrentHashMap<>();
    private final LocalDateTime startedAt;

    public AvailabilityMonitor(
        final LightRegistry lightRegistry,
        final LightStateTracker lightStateTracker,
        final OccupancyRegistry occupancyRegistry,
        final OccupancyTracker occupancyTracker,
        final AvailabilityAlertRepository alertRepository,
        final R2dbcEntityTemplate entityTemplate,
        final NotificationPublisher notificationPublisher,
        final MonitorProperties properties,
        final Clock clock
    ) {
        this.lightRegistry = lightRegistry;
        this.lightStateTracker = lightStateTracker;
        this.occupancyRegistry = occupancyRegistry;
        this.occupancyTracker = occupancyTracker;
        this.alertRepository = alertRepository;
        this.entityTemplate = entityTemplate;
        this.notificationPublisher = notificationPublisher;
        this.properties = properties;
        this.clock = clock;
        this.startedAt = now();
    }

    //Spring subscribes again to the same Mono for every run: everything is read inside the chain. Two minutes
    //in: the lights and the sensors are known by then, and a Gen1 poll has run
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return Mono.defer(() -> {
                final LocalDateTime now = now();
                return lights(now)
                    .map(lights -> Stream.concat(lights.stream(), sensors().stream()).toList())
                    .zipWith(alertRepository.findAll().collectMap(AvailabilityAlertEntity::deviceId, AvailabilityMonitor::toAlert))
                    .flatMap(read -> act(AvailabilityEvaluator.decide(read.getT1(), read.getT2(), now, properties), now));
            })
            .onErrorResume(e -> {
                log.error("The availability check failed: {}", e.toString());
                return Mono.empty();
            });
    }

    private Mono<List<Watched>> lights(final LocalDateTime now) {
        final List<Light> shelly = lightRegistry.current().stream()
            .filter(light -> !(light.binding() instanceof LightBinding.Eaton))
            .toList();
        return Flux.fromIterable(shelly)
            .concatMap(light -> lightStateTracker.status(light.id()).map(status -> {
                if (Boolean.FALSE.equals(status.reachable())) {
                    unreachableSince.putIfAbsent(light.id(), now);
                } else {
                    unreachableSince.remove(light.id());
                }
                return new Watched(light.id(), light.name(), light.room().getValue(), Kind.LIGHT,
                    unreachableSince.get(light.id()));
            }))
            .collectList();
    }

    private List<Watched> sensors() {
        final List<OccupancySensor> watched = occupancyRegistry.sensors().stream()
            .filter(sensor -> !(sensor.binding() instanceof SensorBinding.EatonMotion))
            .toList();
        final Map<RoomName, Map<Long, RoomOccupancy.SensorView>> views = watched.stream()
            .map(OccupancySensor::room)
            .distinct()
            .collect(Collectors.toMap(Function.identity(), room -> occupancyTracker.room(room).sensors().stream()
                .collect(Collectors.toMap(RoomOccupancy.SensorView::id, Function.identity()))));
        return watched.stream()
            .map(sensor -> {
                final RoomOccupancy.SensorView view = views.get(sensor.room()).get(sensor.id());
                final LocalDateTime heard = view == null || view.lastReportAt() == null ? startedAt : view.lastReportAt();
                return new Watched(sensor.id(), sensor.name(), sensor.room().getValue(),
                    sensor.kind() == SensorKind.PRESENCE ? Kind.PRESENCE_SENSOR : Kind.BATTERY_SENSOR, heard);
            })
            .toList();
    }

    //returns first, then the alerts, then the reminders, each after the one before; the table follows the broker
    private Mono<Void> act(final Decision decision, final LocalDateTime now) {
        if (decision.nothing()) {
            return Mono.empty();
        }
        return Flux.fromIterable(decision.forgotten())
            .concatMap(alert -> alertRepository.deleteById(alert.id())
                .doOnSuccess(unused -> log.info("Availability alert of device {} ({}) closed: it is no longer watched",
                    alert.id(), alert.name())))
            .then(publish(decision.returns(), notificationPublisher::publishInfo, returnText(decision.returns(), now))
                .flatMap(sent -> Flux.fromIterable(decision.returns()).concatMap(alert -> alertRepository.deleteById(alert.id())).then()))
            .then(publish(decision.alerts(), notificationPublisher::publishAlert, alertText(decision.alerts(), now))
                .flatMap(sent -> Flux.fromIterable(decision.alerts()).concatMap(alert -> entityTemplate.insert(toEntity(alert, now))).then()))
            .then(publish(decision.reminders(), notificationPublisher::publishReminder, reminderText(decision.reminders(), now))
                .flatMap(sent -> Flux.fromIterable(decision.reminders()).concatMap(alert -> alertRepository.reminded(alert.id(), truncated(now))).then()));
    }

    //Mono.just(true) once the broker took it; empty when there was nothing or it failed (logged, tried again)
    private Mono<Boolean> publish(final List<Alert> alerts, final Function<String, Mono<Void>> publisher, final String text) {
        if (alerts.isEmpty()) {
            return Mono.empty();
        }
        return publisher.apply(text)
            .timeout(PUBLISH_TIMEOUT)
            .thenReturn(true)
            .doOnNext(sent -> log.info("Availability notification published: {}", text))
            .onErrorResume(e -> {
                log.error("Availability notification not published, it is tried again with the next pass: {}", e.toString());
                return Mono.empty();
            });
    }

    String alertText(final List<Alert> alerts, final LocalDateTime now) {
        return "Not available: " + list(alerts, alert -> describe(alert) + state(alert) + " since " + time(alert.since())
            + " (" + span(alert.since(), now) + ")") + ".";
    }

    String reminderText(final List<Alert> alerts, final LocalDateTime now) {
        return "Still not available: " + list(alerts, alert -> describe(alert) + state(alert) + " since " + time(alert.since())
            + " (" + span(alert.since(), now) + ")") + ".";
    }

    String returnText(final List<Alert> alerts, final LocalDateTime now) {
        return "Available again: " + list(alerts, alert -> describe(alert) + " - out from " + time(alert.since()) + " to "
            + time(now) + " (" + span(alert.since(), now) + ")") + ".";
    }

    private static String describe(final Alert alert) {
        final String what = switch (alert.kind()) {
            case LIGHT -> "the light";
            case PRESENCE_SENSOR -> "the presence sensor";
            case BATTERY_SENSOR -> "the motion sensor";
        };
        return what + " " + alert.name() + " (" + alert.room() + ", " + alert.id() + ")";
    }

    private static String state(final Alert alert) {
        return alert.kind() == Kind.LIGHT ? " does not answer" : " is silent";
    }

    private static String list(final List<Alert> alerts, final Function<Alert, String> line) {
        return alerts.stream().map(line).collect(Collectors.joining("; "));
    }

    private String time(final LocalDateTime time) {
        return TIME.format(time);
    }

    private static String span(final LocalDateTime since, final LocalDateTime until) {
        //the minutes of the wall clock, as the times the message names
        final long total = (until.toEpochSecond(ZoneOffset.UTC) - since.toEpochSecond(ZoneOffset.UTC)) / 60;
        final long hours = total / 60;
        final long minutes = total % 60;
        return hours == 0 ? minutes + " min" : hours + " h " + minutes + " min";
    }

    private static Alert toAlert(final AvailabilityAlertEntity row) {
        final Kind kind = Stream.of(Kind.values())
            .filter(candidate -> candidate.value().equals(row.kind()))
            .findFirst()
            .orElse(Kind.LIGHT);
        return new Alert(row.deviceId(), kind, row.name(), row.room(), row.since(), row.lastAlertAt());
    }

    private static AvailabilityAlertEntity toEntity(final Alert alert, final LocalDateTime now) {
        return new AvailabilityAlertEntity(alert.id(), alert.kind().value(), Objects.requireNonNullElse(alert.name(), "?"),
            alert.room(), truncated(alert.since()), truncated(now));
    }

    private static LocalDateTime truncated(final LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.MICROS);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
