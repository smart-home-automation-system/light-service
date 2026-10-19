package cloud.cholewa.light.rule;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.database.RgbColumn;
import cloud.cholewa.light.database.model.MotionPeriodEntity;
import cloud.cholewa.light.database.model.RoomRuleEntity;
import cloud.cholewa.light.database.repository.MotionPeriodRepository;
import cloud.cholewa.light.database.repository.RoomRuleRepository;
import cloud.cholewa.light.database.repository.RuleRunRepository;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.rule.MotionPeriod;
import cloud.cholewa.light.model.rule.RoomRule;
import cloud.cholewa.light.model.rule.RoomRuleBody;
import cloud.cholewa.light.model.rule.RuleRun;
import cloud.cholewa.light.occupancy.OccupancyRegistry;
import cloud.cholewa.light.registry.LightRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

//The rules of the rooms (HAS-261), stored in two tables. A write is one transaction: a rule stays as it
//was while a write fails, its periods included. The body is checked first, then the room against the
//lights and sensors the registry knows; the answer of a write is the rule read back after the commit,
//and it goes into the RuleBook of the engine at once. The reads go one after the other: the pool has two
//connections. The runs of the engine (HAS-262) are read here too
@Slf4j
@Service
@RequiredArgsConstructor
public class RoomRuleService {

    private static final Comparator<MotionPeriod> BY_START = Comparator.comparing(MotionPeriod::from);

    private final RoomRuleRepository ruleRepository;
    private final MotionPeriodRepository periodRepository;
    private final RuleRunRepository runRepository;
    private final RuleBook ruleBook;
    private final LightRegistry lightRegistry;
    private final OccupancyRegistry occupancyRegistry;
    private final TransactionalOperator transactionalOperator;
    private final Clock clock;

    //every room with a rule, by the name of the room as stored
    public Mono<List<RoomRule>> all() {
        return ruleRepository.findAllByOrderByRoom().collectList()
            .flatMap(rules -> periodRepository.findAllByOrderByRoomAscFromTimeAsc().collectList()
                .map(periods -> {
                    final Map<String, List<MotionPeriodEntity>> periodsOf = periods.stream()
                        .collect(Collectors.groupingBy(MotionPeriodEntity::room));
                    return rules.stream()
                        .map(rule -> toRule(rule, periodsOf.getOrDefault(rule.room(), List.of())))
                        .toList();
                }));
    }

    //the same, by the room - for the RuleBook
    public Mono<Map<RoomName, RoomRule>> byRoom() {
        return all().map(rules -> rules.stream()
            .collect(Collectors.toMap(rule -> RoomName.fromValue(rule.room()), Function.identity())));
    }

    public Mono<RoomRule> get(final RoomName room) {
        return existing(room)
            .flatMap(rule -> periodRepository.findByRoomOrderByFromTime(room.name()).collectList()
                .map(periods -> toRule(rule, periods)));
    }

    //whole: creates the rule of the room or replaces it, the switch of the resident is kept. Only the
    //writes are the transaction: the check of the room may read the registry over HTTP, and a
    //connection held meanwhile would be half of the pool
    public Mono<RoomRule> replace(final RoomName room, final RoomRuleBody body) {
        return Mono.fromRunnable(() -> RoomRuleRules.check(body))
            .then(Mono.defer(() -> {
                if (!occupancyRegistry.loaded()) {
                    return Mono.error(LightException::sensorsUnavailable);
                }
                return lightRegistry.all()
                    .doOnNext(lights -> RoomRuleRules.checkRoom(room, body, lights, occupancyRegistry.sensors()));
            }))
            .then(Mono.defer(() -> write(room, body)))
            .then(Mono.fromRunnable(() -> log.info("Rule of the {} written: autoOff {}, motionOn {}, {} periods",
                room.getValue(), body.autoOffEnabled(), body.motionOnEnabled(), body.periods().size())))
            .then(get(room))
            .doOnNext(rule -> ruleBook.put(room, rule));
    }

    private Mono<Void> write(final RoomName room, final RoomRuleBody body) {
        return ruleRepository.upsert(room.name(), body.autoOffEnabled(), body.autoOffDelayMinutes(),
                body.motionOnEnabled(), body.onlyBelowLux(), body.motionOffAfterMinutes(), body.manualHoldMinutes(), now())
            .then(periodRepository.deleteByRoom(room.name()))
            .thenMany(Flux.fromIterable(body.periods().stream().sorted(BY_START).toList())
                .concatMap(period -> periodRepository.save(toEntity(room, period))))
            .then()
            .as(transactionalOperator::transactional);
    }

    //the periods go with it (ON DELETE CASCADE)
    public Mono<Void> remove(final RoomName room) {
        return existing(room)
            .flatMap(rule -> ruleRepository.deleteById(rule.room()))
            .doOnSuccess(removed -> {
                ruleBook.remove(room);
                log.info("Rule of the {} removed", room.getValue());
            });
    }

    //a rule already in that state is not written, so its updatedAt stays
    public Mono<RoomRule> switchByResident(final RoomName room, final boolean enabled) {
        return existing(room)
            .flatMap(rule -> rule.enabledByResident() == enabled
                ? Mono.empty()
                : ruleRepository.switchByResident(room.name(), enabled, now())
                    .doOnNext(changed -> log.info("Rule of the {} {} by the resident", room.getValue(),
                        enabled ? "switched on" : "switched off")))
            .then(get(room))
            .doOnNext(rule -> ruleBook.put(room, rule));
    }

    //what the engine sent in the room, from included and to not, whether the room has a rule now or not
    public Mono<List<RuleRun>> runs(final RoomName room, final LocalDateTime from, final LocalDateTime to) {
        return runRepository.findRuns(room.name(), from, to)
            .map(run -> new RuleRun(run.deviceId(), run.action(), run.reason(), run.error(), run.at()))
            .collectList();
    }

    private Mono<RoomRuleEntity> existing(final RoomName room) {
        return ruleRepository.findById(room.name())
            .switchIfEmpty(Mono.error(() -> LightException.ruleNotFound(room)));
    }

    private static RoomRule toRule(final RoomRuleEntity rule, final List<MotionPeriodEntity> periods) {
        return new RoomRule(
            RoomName.valueOf(rule.room()).getValue(),
            rule.autoOffEnabled(),
            rule.autoOffDelayMin(),
            rule.motionOnEnabled(),
            rule.onlyBelowLux(),
            rule.motionOffAfterMin(),
            periods.stream().map(RoomRuleService::toPeriod).toList(),
            rule.manualHoldMin(),
            rule.enabledByResident(),
            rule.updatedAt()
        );
    }

    private static MotionPeriod toPeriod(final MotionPeriodEntity period) {
        return new MotionPeriod(
            period.fromTime(),
            period.toTime(),
            period.brightness(),
            RgbColumn.values(period.rgb()),
            period.white()
        );
    }

    private static MotionPeriodEntity toEntity(final RoomName room, final MotionPeriod period) {
        return new MotionPeriodEntity(
            null,
            room.name(),
            period.from(),
            period.to(),
            period.brightness(),
            RgbColumn.text(period.rgb()),
            period.white()
        );
    }

    //cut to the microseconds the column keeps, so that the answer of a write reads as a later read
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
