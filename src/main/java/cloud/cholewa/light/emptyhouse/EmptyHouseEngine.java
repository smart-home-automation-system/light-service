package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.commons.error.model.ErrorId;
import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.light.config.EmptyHouseProperties;
import cloud.cholewa.light.database.model.RuleRunEntity;
import cloud.cholewa.light.database.repository.RuleRunRepository;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.emptyhouse.SimulationPlanner.Night;
import cloud.cholewa.light.model.CommandSource;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.Turn;
import cloud.cholewa.light.model.emptyhouse.EmptyHouseStatus;
import cloud.cholewa.light.model.emptyhouse.SimulationPlan;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.light.rule.RuleEvaluator.LightSnapshot;
import cloud.cholewa.light.rule.RuleEvaluator.RuleAction;
import cloud.cholewa.light.service.LightCommandService;
import cloud.cholewa.light.state.LightStateTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

//The empty house (HAS-263). Every minute: where the household is (presence-service), then - while the house
//has been empty for light.empty-house.after and the feature is on - what should be lit now: nothing by day,
//the presence simulation of the night by night (SimulationPlanner, from the stored plan and the sun). The
//lights that differ are switched through LightCommandService with the source empty-house, which starts no
//hold, and every command is logged in light_rule_run. While the house is empty the rules of the rooms pause
//(HouseState.active, read by RuleEngine). Somebody coming back ends it at once - the lights that are on stay
//on, the rules take over.
//One info notification per empty house for what it did by day (the rooms of the lights it switched off), one
//when the simulation starts; a notification never fails or stalls a pass. What it remembers - the commands it
//sent, what it announced - is lost with a restart, which only lets one command or one notification more through
@Slf4j
@Component
@RequiredArgsConstructor
public class EmptyHouseEngine {

    static final Duration PASS_TIMEOUT = Duration.ofSeconds(50);
    static final Duration LIMIT = Duration.ofSeconds(55);
    static final Duration PRESENCE_TIMEOUT = Duration.ofSeconds(10);
    static final Duration NOTIFICATION_TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final int ERROR_LENGTH = 50;

    private final PresenceClient presenceClient;
    private final HouseState houseState;
    private final EmptyHouseProperties properties;
    private final SunCalendar sunCalendar;
    private final SimulationPlanService simulationPlanService;
    private final LightRegistry lightRegistry;
    private final LightStateTracker lightStateTracker;
    private final LightCommandService lightCommandService;
    private final RuleRunRepository ruleRunRepository;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    private final Map<Long, LocalDateTime> commandedAt = new ConcurrentHashMap<>();
    private volatile boolean dayAnnounced;
    private volatile boolean nightAnnounced;
    private volatile Boolean lastNight;

    //Spring subscribes again to the same Mono for every run: everything is read inside the chain.
    //Bounded (HAS-302) a little above its own 50 s, which stays inside: the bound is the last word should
    //anything outside that timeout hang
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> tick() {
        return ScheduledPass.bounded("light empty house", LIMIT, this::tickOnce);
    }

    private Mono<Void> tickOnce() {
        return Mono.defer(() -> presenceClient.residents()
                .timeout(PRESENCE_TIMEOUT)
                .map(residents -> HouseState.judge(residents, now()))
                .onErrorResume(e -> {
                    log.warn("presence-service could not be read, whether anybody is at home is not known: {}", e.toString());
                    return Mono.just(HouseOccupancy.UNKNOWN);
                })
                .flatMap(read -> {
                    final LocalDateTime now = now();
                    if (houseState.update(read, now, properties.after())) {
                        commandedAt.clear();
                        lastNight = null;
                        dayAnnounced = false;
                        nightAnnounced = false;
                    }
                    return properties.enabled() && houseState.active(now) ? act(now) : Mono.empty();
                }))
            .timeout(PASS_TIMEOUT)
            .onErrorResume(e -> {
                log.error("The pass of the empty house failed: {}", e.toString());
                return Mono.empty();
            });
    }

    /** The empty house now, and by night what the simulation lights now and when that changes next. */
    public Mono<EmptyHouseStatus> status() {
        return Mono.defer(() -> {
            final LocalDateTime now = now();
            final boolean active = houseState.active(now);
            return night(now, false)
                .map(night -> {
                    final boolean isNight = night.contains(now);
                    return new EmptyHouseStatus(
                        properties.enabled(),
                        houseState.occupancy().value(),
                        houseState.emptySince(),
                        houseState.actingFrom(),
                        active,
                        night.dusk(),
                        night.sunrise(),
                        isNight && night.phaseAt(now) != null ? night.phaseAt(now).value() : null,
                        isNight ? List.copyOf(night.lightsAt(now)) : List.of(),
                        night.nextChangeAfter(now).orElse(null));
                });
        });
    }

    //by night what the simulation lights now, by day nothing; one decision, its commands one after the other
    private Mono<Void> act(final LocalDateTime now) {
        return night(now, true).flatMap(night -> {
            final boolean isNight = night.contains(now);
            //from day to night and back the guard starts anew: a light the day could not switch off may still be
            //lit by the simulation
            if (lastNight != null && lastNight != isNight) {
                commandedAt.clear();
            }
            lastNight = isNight;
            final Set<Long> lit = isNight ? night.lightsAt(now) : Set.of();
            final List<Light> lights = lightRegistry.current();
            return Flux.fromIterable(lights)
                .concatMap(light -> lightStateTracker.status(light.id()).map(status -> new LightSnapshot(
                    light, status, commandedAt.get(light.id()))))
                .collectList()
                .flatMap(snapshots -> {
                    final List<RuleAction> actions = EmptyHouseEvaluator.decide(snapshots, lit, isNight, houseState.emptySince());
                    actions.forEach(action -> commandedAt.put(action.light().id(), now));
                    return run(actions, now).then(announce(isNight, night, actions));
                });
        });
    }

    //The night a time belongs to, with the plan as stored; the plan is read only by night - by day nothing is lit.
    //A plan that cannot be read skips the pass (strict): taken for no plan it would switch the simulation off and on
    //again with every blip of the database; the status answers it without a plan
    private Mono<Night> night(final LocalDateTime now, final boolean strict) {
        final LocalDate date = SimulationPlanner.nightOf(now);
        final SunDay today = sunCalendar.of(date);
        final SunDay tomorrow = sunCalendar.of(date.plusDays(1));
        final Night bare = SimulationPlanner.night(date, today, tomorrow, SimulationPlan.NONE);
        if (!bare.contains(now)) {
            return Mono.just(bare);
        }
        return simulationPlanService.get()
            .onErrorResume(e -> {
                log.warn("The plan of the presence simulation could not be read{}: {}", strict ? ", the pass is skipped" : "",
                    e.toString());
                return strict ? Mono.empty() : Mono.just(SimulationPlan.NONE);
            })
            .map(plan -> SimulationPlanner.night(date, today, tomorrow, plan));
    }

    //A pass cut off by its timeout leaves the lights it never got to unmarked; the one in flight stays marked
    private Mono<Void> run(final List<RuleAction> actions, final LocalDateTime decidedAt) {
        final Set<Long> started = ConcurrentHashMap.newKeySet();
        return Flux.fromIterable(actions)
            .concatMap(action -> {
                started.add(action.light().id());
                return execute(action);
            })
            .then()
            .doOnCancel(() -> actions.stream()
                .map(action -> action.light().id())
                .filter(id -> !started.contains(id))
                .forEach(id -> commandedAt.remove(id, decidedAt)));
    }

    private Mono<Void> execute(final RuleAction action) {
        final Light light = action.light();
        final LocalDateTime at = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        log.info("Empty house: light {} ({}, {}) {} by {}", light.id(), light.name(), light.room().getValue(),
            action.on() ? "on" : "off", action.reason().value());

        return lightCommandService.turn(light.id(), action.on() ? Turn.ON : Turn.OFF, CommandSource.EMPTY_HOUSE)
            .then(Mono.fromSupplier(Optional::<String>empty))
            .onErrorResume(e -> {
                log.error("Empty house: light {} ({}) did not take the command: {}", light.id(), light.name(), e.getMessage());
                final String code = e instanceof LightException failure
                    ? ErrorId.codeOf(failure.getError())
                    : e.getClass().getSimpleName();
                return Mono.just(Optional.of(code.length() > ERROR_LENGTH ? code.substring(0, ERROR_LENGTH) : code));
            })
            .flatMap(error -> ruleRunRepository.save(new RuleRunEntity(null, light.room().name(), light.id(),
                    action.on() ? "on" : "off", action.reason().value(), error.orElse(null), at))
                .then()
                .onErrorResume(e -> {
                    log.warn("The run of the empty house for light {} could not be logged: {}", light.id(), e.toString());
                    return Mono.empty();
                }));
    }

    private Mono<Void> announce(final boolean isNight, final Night night, final List<RuleAction> actions) {
        final LocalDateTime since = houseState.emptySince();
        final String sinceText = since == null ? "a while" : since.format(HOUR);
        //no plan, or no phase on: nothing to announce
        if (isNight && !nightAnnounced && !night.segments().isEmpty()) {
            nightAnnounced = true;
            return publish("Nobody is at home since " + sinceText + " - the presence simulation runs until the sunrise at "
                + night.sunrise().format(HOUR) + ".");
        }
        //at the sunrise the simulation switches its own lights off: that is not the lights left on of the day
        if (!isNight && !dayAnnounced && !nightAnnounced && actions.stream().anyMatch(action -> !action.on())) {
            dayAnnounced = true;
            final String rooms = actions.stream().filter(action -> !action.on())
                .map(action -> action.light().room().getValue()).distinct().collect(Collectors.joining(", "));
            return publish("Nobody is at home since " + sinceText + " - the lights left on are switched off: " + rooms + ".");
        }
        return Mono.empty();
    }

    private Mono<Void> publish(final String text) {
        return notificationPublisher.publishInfo(text)
            .timeout(NOTIFICATION_TIMEOUT)
            .onErrorResume(e -> {
                log.error("Notification of the empty house not published: {}", e.toString());
                return Mono.empty();
            });
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
