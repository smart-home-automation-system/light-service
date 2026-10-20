package cloud.cholewa.light.monitor;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.config.MonitorProperties;
import cloud.cholewa.light.database.model.AvailabilityAlertEntity;
import cloud.cholewa.light.database.repository.AvailabilityAlertRepository;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.model.LightStatus;
import cloud.cholewa.light.model.RoomOccupancy;
import cloud.cholewa.light.occupancy.OccupancyRegistry;
import cloud.cholewa.light.occupancy.OccupancySensor;
import cloud.cholewa.light.occupancy.OccupancyTracker;
import cloud.cholewa.light.occupancy.SensorBinding;
import cloud.cholewa.light.occupancy.SensorKind;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.light.state.LightStateTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The availability monitor around its evaluator (HAS-264): what it watches, one message for many devices, the
//table moved only once the broker took the message
class AvailabilityMonitorTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 20, 12, 0);
    private static final Light TOBI_MAIN = new Light(110, "Main", RoomName.TOBI, SmartVendor.SHELLY,
        new LightBinding.ShellyGen2("10.0.0.176", 0, "shelly1pmg3-b0"), LightKind.RELAY);
    private static final Light LIVIA_DESK = new Light(113, "Desk", RoomName.LIVIA, SmartVendor.SHELLY,
        new LightBinding.ShellyGen1("10.0.0.205", 0), LightKind.RELAY);
    private static final Light STAIRS = new Light(45, "Stairs", RoomName.STAIRS, SmartVendor.EATON,
        new LightBinding.Eaton(21, EatonGatewayType.LIGHTS), LightKind.DIMMER);
    private static final OccupancySensor PRESENCE = new OccupancySensor(92, "Presence", RoomName.LIVIA, SensorKind.PRESENCE,
        new SensorBinding.Presence("10.0.0.62", "shellypresence-62", 200));
    private static final OccupancySensor PIR = new OccupancySensor(51, "PIR", RoomName.STAIRS, SensorKind.MOTION,
        new SensorBinding.EatonMotion(EatonGatewayType.LIGHTS, 92));

    private final AtomicReference<LocalDateTime> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return HOUSE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get().atZone(HOUSE).toInstant();
        }
    };

    private final LightRegistry lightRegistry = mock(LightRegistry.class);
    private final LightStateTracker lightStateTracker = mock(LightStateTracker.class);
    private final OccupancyRegistry occupancyRegistry = mock(OccupancyRegistry.class);
    private final OccupancyTracker occupancyTracker = mock(OccupancyTracker.class);
    private final AvailabilityAlertRepository alertRepository = mock(AvailabilityAlertRepository.class);
    private final R2dbcEntityTemplate entityTemplate = mock(R2dbcEntityTemplate.class);
    private final NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
    private AvailabilityMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new AvailabilityMonitor(lightRegistry, lightStateTracker, occupancyRegistry, occupancyTracker, alertRepository,
            entityTemplate, notificationPublisher, new MonitorProperties(Duration.ofMinutes(5), Duration.ofHours(1),
            Duration.ofMinutes(30), Duration.ofHours(24)), clock);
        when(lightRegistry.loaded()).thenReturn(true);
        when(occupancyRegistry.loaded()).thenReturn(true);
        when(lightRegistry.current()).thenReturn(List.of(TOBI_MAIN, LIVIA_DESK, STAIRS));
        when(lightStateTracker.status(anyLong())).thenReturn(Mono.just(reachable(true)));
        when(occupancyRegistry.sensors()).thenReturn(List.of(PRESENCE, PIR));
        when(occupancyTracker.room(RoomName.LIVIA)).thenAnswer(invocation -> occupancy(now.get()));
        when(alertRepository.findAll()).thenReturn(Flux.empty());
        when(entityTemplate.insert(any(AvailabilityAlertEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(alertRepository.deleteById(anyLong())).thenReturn(Mono.empty());
        when(alertRepository.reminded(anyLong(), any())).thenReturn(Mono.just(1));
        when(notificationPublisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(notificationPublisher.publishInfo(anyString())).thenReturn(Mono.empty());
        when(notificationPublisher.publishReminder(anyString())).thenReturn(Mono.empty());
    }

    //two Shelly lights out together: one alert naming both, two rows; the Eaton light is never asked
    @Test
    void should_announce_every_light_out_in_one_alert() {
        when(lightStateTracker.status(TOBI_MAIN.id())).thenReturn(Mono.just(reachable(false)));
        when(lightStateTracker.status(LIVIA_DESK.id())).thenReturn(Mono.just(reachable(false)));

        check();
        now.set(START.plusMinutes(5));
        check();

        verify(notificationPublisher, times(1)).publishAlert(
            "Not available: the light Main (tobi, 110) does not answer since 2026-10-20 12:00 (5 min); "
                + "the light Desk (livia, 113) does not answer since 2026-10-20 12:00 (5 min).");
        verify(entityTemplate, times(2)).insert(any(AvailabilityAlertEntity.class));
        verify(lightStateTracker, never()).status(STAIRS.id());
    }

    //the broker away: nothing written, the alert tried again with the next pass
    @Test
    void should_write_nothing_when_the_alert_is_not_published() {
        when(lightStateTracker.status(TOBI_MAIN.id())).thenReturn(Mono.just(reachable(false)));
        when(notificationPublisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker away")));
        check();
        now.set(START.plusMinutes(5));

        check();

        verify(entityTemplate, never()).insert(any(AvailabilityAlertEntity.class));
    }

    //a reachability not known after a start is not judged
    @Test
    void should_not_judge_a_light_whose_reachability_is_not_known() {
        when(lightStateTracker.status(TOBI_MAIN.id())).thenReturn(Mono.just(LightStatus.unknown()));
        check();
        now.set(START.plusHours(1));

        check();

        verify(notificationPublisher, never()).publishAlert(anyString());
    }

    //after a restart the light is not known yet: its alert stays open, no false return
    @Test
    void should_keep_the_alert_of_a_light_not_known_after_a_restart() {
        when(lightStateTracker.status(TOBI_MAIN.id())).thenReturn(Mono.just(LightStatus.unknown()));
        when(alertRepository.findAll()).thenReturn(Flux.just(new AvailabilityAlertEntity(110L, "light", "Main", "tobi",
            START.minusHours(1), START.minusMinutes(30))));

        check();

        verify(notificationPublisher, never()).publishInfo(anyString());
        verify(alertRepository, never()).deleteById(110L);
    }

    @Test
    void should_announce_a_return_and_close_the_alert() {
        when(alertRepository.findAll()).thenReturn(Flux.just(new AvailabilityAlertEntity(110L, "light", "Main", "tobi",
            START.minusHours(1), START.minusMinutes(30))));

        check();

        verify(notificationPublisher).publishInfo(
            "Available again: the light Main (tobi, 110) - out from 2026-10-20 11:00 to 2026-10-20 12:00 (1 h 0 min).");
        verify(alertRepository).deleteById(110L);
    }

    @Test
    void should_remind_an_outage_after_an_hour() {
        when(lightStateTracker.status(TOBI_MAIN.id())).thenReturn(Mono.just(reachable(false)));
        when(alertRepository.findAll()).thenReturn(Flux.just(new AvailabilityAlertEntity(110L, "light", "Main", "tobi",
            START.minusHours(2), START.minusHours(1))));
        check();
        now.set(START.plusMinutes(5));

        check();

        verify(notificationPublisher).publishReminder(anyString());
        verify(alertRepository).reminded(110L, START.plusMinutes(5));
    }

    //a sensor never heard since the start counts from the start; the PIR of the AMX system is not watched
    @Test
    void should_count_a_sensor_never_heard_from_the_start() {
        when(occupancyTracker.room(RoomName.LIVIA)).thenReturn(occupancy(null));
        now.set(START.plusMinutes(30));

        check();

        verify(notificationPublisher).publishAlert(
            "Not available: the presence sensor Presence (livia, 92) is silent since 2026-10-20 12:00 (30 min).");
        verify(occupancyTracker, never()).room(RoomName.STAIRS);
    }

    //a start while database-service is away: nothing is judged, no alert forgotten
    @Test
    void should_judge_nothing_before_both_registries_were_read() {
        when(lightRegistry.loaded()).thenReturn(false);
        when(lightRegistry.current()).thenReturn(List.of());
        when(alertRepository.findAll()).thenReturn(Flux.just(new AvailabilityAlertEntity(110L, "light", "Main", "tobi",
            START.minusHours(1), START.minusMinutes(30))));

        check();

        verify(alertRepository, never()).deleteById(anyLong());
        verify(notificationPublisher, never()).publishInfo(anyString());
    }

    //removed from the registry while out: the alert closed without a word
    @Test
    void should_close_the_alert_of_a_device_no_longer_watched_without_a_word() {
        when(alertRepository.findAll()).thenReturn(Flux.just(new AvailabilityAlertEntity(999L, "light", "Gone", "loft",
            START.minusHours(1), START.minusMinutes(30))));

        check();

        verify(alertRepository).deleteById(999L);
        verify(notificationPublisher, never()).publishInfo(anyString());
    }

    //a Wi-Fi outage: ten devices by name, the rest counted
    @Test
    void should_name_at_most_ten_devices_in_one_message() {
        final List<AvailabilityEvaluator.Alert> alerts = IntStream.range(0, 12)
            .mapToObj(i -> new AvailabilityEvaluator.Alert(200 + i, AvailabilityEvaluator.Kind.LIGHT, "L" + i, "loft",
                START, START))
            .toList();

        assertThat(monitor.alertText(alerts, START.plusMinutes(5)))
            .contains("the light L9 (loft, 209)")
            .doesNotContain("L10 ")
            .endsWith("; and 2 more.");
    }

    @Test
    void should_never_fail_a_check() {
        when(alertRepository.findAll()).thenReturn(Flux.error(new IllegalStateException("database away")));

        check();
    }

    private void check() {
        monitor.check().as(StepVerifier::create).verifyComplete();
    }

    private static RoomOccupancy occupancy(final LocalDateTime lastReportAt) {
        return new RoomOccupancy("livia", false, START, null, null, null,
            List.of(new RoomOccupancy.SensorView(92, "Presence", "presence sensor", lastReportAt, true)));
    }

    private static LightStatus reachable(final boolean reachable) {
        return new LightStatus(false, null, null, null, START.minusHours(1), "external", reachable);
    }
}
