package cloud.cholewa.light.monitor;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.light.config.MonitorProperties;
import cloud.cholewa.light.database.model.AvailabilityAlertEntity;
import cloud.cholewa.light.database.model.BatteryAlertEntity;
import cloud.cholewa.light.database.repository.AvailabilityAlertRepository;
import cloud.cholewa.light.database.repository.BatteryAlertRepository;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.monitor.AvailabilityEvaluator.Alert;
import cloud.cholewa.light.monitor.AvailabilityEvaluator.Decision;
import cloud.cholewa.light.monitor.AvailabilityEvaluator.Kind;
import cloud.cholewa.light.monitor.AvailabilityEvaluator.Watched;
import cloud.cholewa.light.occupancy.OccupancyRegistry;
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
import java.util.Optional;
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
//- A sensor is heard when it reports (OccupancyTracker), a BLU Motion also by any packet a gateway heard of it
//  (HAS-529); one never heard since the start counts from the start. The PIRs of the AMX system (they report only
//  after dark) and the Shelly Motion Gen1 (it sleeps, no heartbeat - HAS-529) are not watched.
//- HAS-530: the battery of every BLU Motion against light.monitor.battery-low-below - one warning naming the sensors
//  newly below it (category alert, level warn: the technical channel, never the household, never an SMS), no
//  reminder, one info for the ones back above it; kept in light_battery_alert. Its own error handling: a failed
//  battery pass never keeps the availability from being judged, nor the other way round.
//- The table changes only once the broker has taken the message; a notification that fails is tried again with
//  the next pass, and neither fails nor stalls the pass for longer than PUBLISH_TIMEOUT
@Slf4j
@Component
public class AvailabilityMonitor {

    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);
    //below the fixed delay; a healthy publish takes milliseconds - a broker so slow that three publishes of one
    //pass run into it leaves the alert rows unwritten, and the next pass tries again, as after a failed publish
    static final Duration LIMIT = Duration.ofSeconds(55);
    static final int MAX_LISTED = 10;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final LightRegistry lightRegistry;
    private final LightStateTracker lightStateTracker;
    private final OccupancyRegistry occupancyRegistry;
    private final OccupancyTracker occupancyTracker;
    private final AvailabilityAlertRepository alertRepository;
    private final BatteryAlertRepository batteryAlertRepository;
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
        final BatteryAlertRepository batteryAlertRepository,
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
        this.batteryAlertRepository = batteryAlertRepository;
        this.entityTemplate = entityTemplate;
        this.notificationPublisher = notificationPublisher;
        this.properties = properties;
        this.clock = clock;
        this.startedAt = now();
    }

    //Spring subscribes again to the same Mono for every run: everything is read inside the chain. Two minutes
    //in: the lights and the sensors are known by then, and a Gen1 poll has run
    //Bounded (HAS-302): the trackers, the alerts of the database and at most a notification a kind (each cut
    //off after 30 s), then the batteries (HAS-530, two more at most) - a pass beyond this is hung and ends, a part it
    //never got to included; the next one judges again
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return ScheduledPass.bounded("light availability monitor", LIMIT, () -> checkOnce().then(checkBatteries()));
    }

    private Mono<Void> checkOnce() {
        return Mono.defer(() -> {
                //before both registries were read every device would look removed, and its alert be forgotten
                if (!lightRegistry.loaded() || !occupancyRegistry.loaded()) {
                    return Mono.empty();
                }
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

    //HAS-530: the battery of every BLU Motion of the registry, a level not known never low
    private Mono<Void> checkBatteries() {
        return Mono.defer(() -> {
                if (!occupancyRegistry.loaded()) {
                    return Mono.empty();
                }
                final LocalDateTime now = now();
                final List<BatteryEvaluator.Level> levels = occupancyRegistry.sensors().stream()
                    .filter(sensor -> sensor.binding() instanceof SensorBinding.BluMotion)
                    .map(sensor -> new BatteryEvaluator.Level(sensor.id(), sensor.name(), sensor.room().getValue(),
                        occupancyTracker.batteryOf(sensor).orElse(null)))
                    .toList();
                return batteryAlertRepository.findAll()
                    .collectMap(BatteryAlertEntity::deviceId, AvailabilityMonitor::toWarning)
                    .flatMap(open -> actOnBatteries(BatteryEvaluator.decide(levels, open, now, properties.batteryLowBelow())));
            })
            .onErrorResume(e -> {
                log.error("The battery check failed: {}", e.toString());
                return Mono.empty();
            });
    }

    //returns first, then the warnings; the table follows the broker, as for the availability
    private Mono<Void> actOnBatteries(final BatteryEvaluator.Decision decision) {
        if (decision.nothing()) {
            return Mono.empty();
        }
        return Flux.fromIterable(decision.forgotten())
            .concatMap(warning -> batteryAlertRepository.deleteById(warning.id())
                .doOnSuccess(unused -> log.info("Battery warning of device {} ({}) closed: it is no longer watched",
                    warning.id(), warning.name())))
            .then(publish(decision.returns().isEmpty(), notificationPublisher::publishInfo, batteryBackText(decision.returns()), "Battery")
                .flatMap(sent -> Flux.fromIterable(decision.returns())
                    .concatMap(level -> batteryAlertRepository.deleteById(level.id())).then()))
            .then(publish(decision.warnings().isEmpty(), notificationPublisher::publishReminder, batteryLowText(decision.warnings()), "Battery")
                .flatMap(sent -> Flux.fromIterable(decision.warnings())
                    .concatMap(warning -> entityTemplate.insert(toEntity(warning))).then()));
    }

    String batteryLowText(final List<BatteryEvaluator.Warning> warnings) {
        return "Low battery: " + warnings.stream().limit(MAX_LISTED)
            .map(warning -> "the motion sensor " + warning.name() + " (" + warning.room() + ", " + warning.id() + ") at "
                + warning.level() + " %")
            .collect(Collectors.joining("; ")) + more(warnings.size()) + " - below " + properties.batteryLowBelow()
            + " %, replace the battery.";
    }

    String batteryBackText(final List<BatteryEvaluator.Level> levels) {
        return "Battery fine again: " + levels.stream().limit(MAX_LISTED)
            .map(level -> "the motion sensor " + level.name() + " (" + level.room() + ", " + level.id() + ") at "
                + level.level() + " %")
            .collect(Collectors.joining("; ")) + more(levels.size()) + ".";
    }

    private static String more(final int size) {
        return size > MAX_LISTED ? "; and " + (size - MAX_LISTED) + " more" : "";
    }

    private static BatteryEvaluator.Warning toWarning(final BatteryAlertEntity row) {
        return new BatteryEvaluator.Warning(row.deviceId(), row.name(), row.room(), row.level(), row.since());
    }

    private static BatteryAlertEntity toEntity(final BatteryEvaluator.Warning warning) {
        return new BatteryAlertEntity(warning.id(), Objects.requireNonNullElse(warning.name(), "?"), warning.room(),
            warning.level(), truncated(warning.since()));
    }

    private Mono<List<Watched>> lights(final LocalDateTime now) {
        final List<Light> shelly = lightRegistry.current().stream()
            .filter(light -> !(light.binding() instanceof LightBinding.Eaton))
            .toList();
        return Flux.fromIterable(shelly)
            .concatMap(light -> lightStateTracker.status(light).map(status -> {
                if (Boolean.FALSE.equals(status.reachable())) {
                    unreachableSince.putIfAbsent(light.id(), now);
                } else {
                    unreachableSince.remove(light.id());
                }
                return new Watched(light.id(), light.name(), light.room().getValue(), Kind.LIGHT,
                    unreachableSince.get(light.id()), status.reachable() != null);
            }))
            .collectList();
    }

    //the presence sensors and the BLU Motions; a BLU Motion by the last packet a gateway heard of it, motion or not
    //(HAS-529). A Shelly Motion Gen1 sleeps on Wi-Fi with no heartbeat and a PIR of the AMX system reports only after
    //dark: neither is watched - an open alert of one is closed without a word as no longer watched
    private List<Watched> sensors() {
        return occupancyRegistry.sensors().stream()
            .filter(sensor -> sensor.binding() instanceof SensorBinding.Presence
                || sensor.binding() instanceof SensorBinding.BluMotion)
            .map(sensor -> {
                final Optional<LocalDateTime> heard = occupancyTracker.lastHeardAt(sensor);
                return new Watched(sensor.id(), sensor.name(), sensor.room().getValue(),
                    sensor.kind() == SensorKind.PRESENCE ? Kind.PRESENCE_SENSOR : Kind.BATTERY_SENSOR,
                    heard.orElse(startedAt), heard.isPresent());
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
        return publish(alerts.isEmpty(), publisher, text, "Availability");
    }

    //what: the word that starts the log lines - Availability or Battery
    private Mono<Boolean> publish(final boolean nothing, final Function<String, Mono<Void>> publisher, final String text,
                                  final String what) {
        if (nothing) {
            return Mono.empty();
        }
        return publisher.apply(text)
            .timeout(PUBLISH_TIMEOUT)
            .thenReturn(true)
            .doOnNext(sent -> log.info("{} notification published: {}", what, text))
            .onErrorResume(e -> {
                log.error("{} notification not published, it is tried again with the next pass: {}", what, e.toString());
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

    //at most MAX_LISTED devices by name, the rest counted: a Discord embed holds 4096 characters, and a Wi-Fi
    //outage takes thirty devices at once
    private static String list(final List<Alert> alerts, final Function<Alert, String> line) {
        final String listed = alerts.stream().limit(MAX_LISTED).map(line).collect(Collectors.joining("; "));
        return alerts.size() > MAX_LISTED ? listed + "; and " + (alerts.size() - MAX_LISTED) + " more" : listed;
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
            .orElseGet(() -> {
                log.warn("Availability alert of device {} has a kind this version does not know: {}", row.deviceId(), row.kind());
                return Kind.LIGHT;
            });
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
