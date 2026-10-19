package cloud.cholewa.light.rule;

import cloud.cholewa.commons.error.model.ErrorId;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.database.model.RuleRunEntity;
import cloud.cholewa.light.database.repository.RuleRunRepository;
import cloud.cholewa.light.emptyhouse.HouseState;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Color;
import cloud.cholewa.light.model.CommandSource;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightStatus;
import cloud.cholewa.light.model.Turn;
import cloud.cholewa.light.model.rule.MotionPeriod;
import cloud.cholewa.light.model.rule.RoomRule;
import cloud.cholewa.light.occupancy.OccupancyTracker;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.light.rule.RuleEvaluator.LightSnapshot;
import cloud.cholewa.light.rule.RuleEvaluator.RoomSnapshot;
import cloud.cholewa.light.rule.RuleEvaluator.RuleAction;
import cloud.cholewa.light.rule.RuleEvaluator.Verdict;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

//The rules engine (HAS-262): runs RuleEvaluator against the rooms with a rule and sends what it decides
//through LightCommandService with the source rule - so the change a command makes is the rule's, and starts
//no hold. Two triggers: a fresh motion of a sensor (motion-on, at once) and the pass of every minute
//(auto-off, motion-off, the rules read again). A motion is reacted to apart from the event that brought it,
//with its context (the trace goes on): neither the device queue nor the HTTP call of a Shelly Motion waits for
//the commands, and neither can cancel them halfway. Everything runs in the memory of the one instance; what it
//remembers - when it last commanded a light, when it last named a room it could not judge - is lost with a
//restart, which only lets one command more through.
//Every command is logged in light_rule_run, whatever its outcome; a log that cannot be written is a WARN,
//never a failed pass. A command that fails is not repeated until the light reports a change
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleEngine {

    //a pass ends well before the next one is due
    static final Duration PASS_TIMEOUT = Duration.ofSeconds(50);
    //a room that cannot be judged is named this often, not every minute
    static final Duration SKIP_LOG_EVERY = Duration.ofHours(1);
    //a BLU Motion reports its motion and its lux as two components of one packet, published one after the
    //other: the lux of the motion's own packet is waited for before the room is judged by it
    static final Duration SETTLE = Duration.ofSeconds(1);
    private static final int ERROR_LENGTH = 50;

    private final RoomRuleService roomRuleService;
    private final RuleBook ruleBook;
    private final LightRegistry lightRegistry;
    private final LightStateTracker lightStateTracker;
    private final OccupancyTracker occupancyTracker;
    private final LightCommandService lightCommandService;
    private final RuleRunRepository ruleRunRepository;
    private final HouseState houseState;
    private final Clock clock;

    private final Map<Long, LocalDateTime> commandedAt = new ConcurrentHashMap<>();
    private final Map<RoomName, LocalDateTime> skipLoggedAt = new ConcurrentHashMap<>();
    //zero in the tests
    Duration settleDelay = SETTLE;

    //Spring subscribes again to the same Mono for every run: everything is read inside the chain
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> tick() {
        return Mono.defer(() -> roomRuleService.byRoom()
                .doOnNext(ruleBook::replace)
                .then()
                .onErrorResume(e -> {
                    log.warn("The rules of the rooms could not be read, the last ones run: {}", e.toString());
                    return Mono.empty();
                })
                //the rules pause while nobody is at home: they would switch the presence simulation off (HAS-263)
                .thenMany(Flux.defer(() -> houseState.active(now())
                    ? Flux.empty()
                    : Flux.fromIterable(ruleBook.all().entrySet())))
                .concatMap(entry -> pass(entry.getKey(), entry.getValue()))
                .then())
            .timeout(PASS_TIMEOUT)
            .onErrorResume(e -> {
                log.error("The pass of the rules failed: {}", e.toString());
                return Mono.empty();
            });
    }

    /**
     * A sensor of the room just saw a motion. Completes at once: the reaction runs on its own, with the context
     * of the caller, and never fails - a failure is logged.
     */
    public Mono<Void> motion(final RoomName room) {
        return Mono.deferContextual(context -> {
            final RoomRule rule = ruleBook.rule(room);
            if (rule == null || !rule.enabledByResident() || !rule.motionOnEnabled() || houseState.active(now())) {
                return Mono.empty();
            }
            (settleDelay.isZero() ? Mono.<Void>empty() : Mono.delay(settleDelay).then())
                .then(Mono.defer(() -> decide(room, rule, snapshot -> RuleEvaluator.motion(snapshot, now()))))
                .flatMap(decision -> run(room, decision))
                .onErrorResume(e -> {
                    log.error("The motion rule of the {} failed: {}", room.getValue(), e.toString());
                    return Mono.empty();
                })
                .contextWrite(context)
                .subscribe(null, e -> log.error("The motion rule of the {} failed: {}", room.getValue(), e.toString()));
            return Mono.empty();
        });
    }

    //one room of the pass, with its own error handling: one room that fails stops none after it
    private Mono<Void> pass(final RoomName room, final RoomRule rule) {
        return decide(room, rule, snapshot -> {
                final Verdict verdict = RuleEvaluator.tick(snapshot, now());
                skipped(room, verdict.skipped());
                return verdict.actions();
            })
            .flatMap(decision -> run(room, decision))
            .onErrorResume(e -> {
                log.error("The rules of the {} failed: {}", room.getValue(), e.toString());
                return Mono.empty();
            });
    }

    //The states are read first; the decision and the note of the commands it makes are taken together, so a
    //motion and the pass deciding at once never both command the same light
    private Mono<Decision> decide(
        final RoomName room,
        final RoomRule rule,
        final Function<RoomSnapshot, List<RuleAction>> evaluate
    ) {
        final List<Light> lights = lightRegistry.current().stream().filter(light -> light.room() == room).toList();
        if (lights.isEmpty()) {
            return Mono.just(new Decision(List.of(), now()));
        }
        return Flux.fromIterable(lights)
            .concatMap(light -> lightStateTracker.status(light.id()).map(status -> new LightState(light, status)))
            .collectList()
            .map(states -> {
                synchronized (commandedAt) {
                    final LocalDateTime now = now();
                    final RoomSnapshot snapshot = new RoomSnapshot(rule, occupancyTracker.room(room), states.stream()
                        .map(state -> new LightSnapshot(state.light(), state.status(), commandedAt.get(state.light().id())))
                        .toList());
                    final List<RuleAction> actions = evaluate.apply(snapshot);
                    actions.forEach(action -> commandedAt.put(action.light().id(), now));
                    return new Decision(actions, now);
                }
            });
    }

    //One command after the other. A pass cut off by its timeout leaves the lights it never got to unmarked -
    //a command that was not sent must not hold the next one back; the one in flight stays marked, it may
    //have been carried out
    private Mono<Void> run(final RoomName room, final Decision decision) {
        final Set<Long> started = ConcurrentHashMap.newKeySet();
        return Flux.fromIterable(decision.actions())
            .concatMap(action -> {
                started.add(action.light().id());
                return execute(room, action);
            })
            .then()
            .doOnCancel(() -> decision.actions().stream()
                .map(action -> action.light().id())
                .filter(id -> !started.contains(id))
                .forEach(id -> commandedAt.remove(id, decision.at())));
    }

    private void skipped(final RoomName room, final String why) {
        if (why == null) {
            skipLoggedAt.remove(room);
            return;
        }
        final LocalDateTime now = now();
        final LocalDateTime logged = skipLoggedAt.get(room);
        if (logged == null || !logged.plus(SKIP_LOG_EVERY).isAfter(now)) {
            skipLoggedAt.put(room, now);
            log.info("Auto-off of the {} skipped: {}", room.getValue(), why);
        }
    }

    private Mono<Void> execute(final RoomName room, final RuleAction action) {
        final Light light = action.light();
        final LocalDateTime at = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        log.info("Rule of the {}: light {} ({}) {} by {}", room.getValue(), light.id(), light.name(),
            action.on() ? "on" : "off", action.reason().value());

        return command(action)
            .then(Mono.fromSupplier(Optional::<String>empty))
            //LightCommandService leaves nothing but a LightException, and has logged what it is
            .onErrorResume(e -> {
                log.error("Rule of the {}: light {} ({}) did not take the command: {}", room.getValue(), light.id(),
                    light.name(), e.getMessage());
                //the width of the column
                final String code = e instanceof LightException failure
                    ? ErrorId.codeOf(failure.getError())
                    : e.getClass().getSimpleName();
                return Mono.just(Optional.of(code.length() > ERROR_LENGTH ? code.substring(0, ERROR_LENGTH) : code));
            })
            .flatMap(error -> ruleRunRepository.save(new RuleRunEntity(null, room.name(), light.id(),
                    action.on() ? "on" : "off", action.reason().value(), error.orElse(null), at))
                .then()
                .onErrorResume(e -> {
                    log.warn("The run of the rule of the {} for light {} could not be logged: {}", room.getValue(),
                        light.id(), e.toString());
                    return Mono.empty();
                }));
    }

    //off is off; on is what the period gives and the light can take - a colour for an rgbw light, a
    //brightness for one that dims, otherwise on as the light keeps itself
    private Mono<Void> command(final RuleAction action) {
        final Light light = action.light();
        if (!action.on()) {
            return lightCommandService.turn(light.id(), Turn.OFF, CommandSource.RULE);
        }
        final MotionPeriod period = action.period();
        if (period != null && light.colors() && period.colored()) {
            return lightCommandService.color(light.id(), new Color(period.rgb(), period.white(), period.brightness()),
                CommandSource.RULE);
        }
        if (period != null && light.dims() && period.brightness() != null) {
            return lightCommandService.brightness(light.id(), period.brightness(), CommandSource.RULE);
        }
        return lightCommandService.turn(light.id(), Turn.ON, CommandSource.RULE);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private record LightState(Light light, LightStatus status) {
    }

    //what one decision commands, and when it was taken - the mark it left on each light
    private record Decision(List<RuleAction> actions, LocalDateTime at) {
    }
}
