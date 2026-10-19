package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.database.model.SimulationPhaseEntity;
import cloud.cholewa.light.database.model.SimulationSpotEntity;
import cloud.cholewa.light.database.repository.SimulationPhaseRepository;
import cloud.cholewa.light.database.repository.SimulationSpotRepository;
import cloud.cholewa.light.model.emptyhouse.PhaseName;
import cloud.cholewa.light.model.emptyhouse.PhasePlan;
import cloud.cholewa.light.model.emptyhouse.SimulationPlan;
import cloud.cholewa.light.model.emptyhouse.Spot;
import cloud.cholewa.light.model.emptyhouse.SpotRole;
import cloud.cholewa.light.registry.LightRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

//The plan of the presence simulation (HAS-263), stored in two tables and written whole: the body is checked
//first, then its lights against the registry, then the plan replaces the stored one in one transaction - a
//plan stays as it was while a write fails. Only the writes are the transaction: the registry may be read over
//HTTP before, and a connection held meanwhile would be half of the pool. The answer of a write is the plan
//read back after the commit
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationPlanService {

    private final SimulationPhaseRepository phaseRepository;
    private final SimulationSpotRepository spotRepository;
    private final R2dbcEntityTemplate entityTemplate;
    private final LightRegistry lightRegistry;
    private final TransactionalOperator transactionalOperator;
    private final Clock clock;

    //the phases in the order they come, each with its spots in the order they were written
    public Mono<SimulationPlan> get() {
        return phaseRepository.findAll().collectList()
            .flatMap(phases -> spotRepository.findAllByOrderByPhaseAscPositionAsc().collectList()
                .map(spots -> {
                    final Map<String, List<SimulationSpotEntity>> spotsOf = spots.stream()
                        .collect(Collectors.groupingBy(SimulationSpotEntity::phase));
                    return new SimulationPlan(phases.stream()
                        .sorted(Comparator.comparing(phase -> PhaseName.valueOf(phase.phase())))
                        .map(phase -> toPhase(phase, spotsOf.getOrDefault(phase.phase(), List.of())))
                        .toList());
                }));
    }

    public Mono<SimulationPlan> replace(final SimulationPlan plan) {
        return Mono.fromRunnable(() -> PlanRules.check(plan))
            .then(Mono.defer(lightRegistry::all))
            .doOnNext(lights -> PlanRules.checkLights(plan, lights))
            .then(Mono.defer(() -> write(plan)))
            .then(Mono.fromRunnable(() -> log.info("Presence simulation plan written: {}",
                plan.phases().stream().map(phase -> phase.phase().value() + (phase.enabled() ? "" : " (off)"))
                    .collect(Collectors.joining(", ")))))
            .then(get());
    }

    private Mono<Void> write(final SimulationPlan plan) {
        final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        return phaseRepository.deleteEveryPhase()
            .thenMany(Flux.fromIterable(plan.phases())
                .concatMap(phase -> entityTemplate.insert(toEntity(phase, now))
                    .thenMany(Flux.fromStream(IntStream.range(0, phase.spots().size())
                        .mapToObj(position -> toEntity(phase.phase(), position, phase.spots().get(position)))))
                    .concatMap(spotRepository::save)))
            .then()
            .as(transactionalOperator::transactional);
    }

    private static PhasePlan toPhase(final SimulationPhaseEntity phase, final List<SimulationSpotEntity> spots) {
        return new PhasePlan(
            PhaseName.valueOf(phase.phase()),
            phase.enabled(),
            phase.fromTime(),
            phase.toTime(),
            phase.jitterMin(),
            phase.minStayMin(),
            phase.maxStayMin(),
            phase.maxSpots(),
            phase.visitsMin(),
            phase.visitsMax(),
            spots.stream().map(SimulationPlanService::toSpot).toList()
        );
    }

    private static Spot toSpot(final SimulationSpotEntity spot) {
        return new Spot(
            SpotRole.of(spot.role()),
            RoomName.valueOf(spot.room()).getValue(),
            Arrays.stream(spot.lightIds().split(",")).map(Long::valueOf).toList()
        );
    }

    private static SimulationPhaseEntity toEntity(final PhasePlan phase, final LocalDateTime now) {
        return new SimulationPhaseEntity(
            phase.phase().name(),
            phase.enabled(),
            phase.from(),
            phase.to(),
            phase.jitterMinutes(),
            phase.minStayMinutes(),
            phase.maxStayMinutes(),
            phase.maxSpotsAtOnce(),
            phase.visitsMin(),
            phase.visitsMax(),
            now
        );
    }

    private static SimulationSpotEntity toEntity(final PhaseName phase, final int position, final Spot spot) {
        return new SimulationSpotEntity(
            null,
            phase.name(),
            position,
            spot.role().value(),
            PlanRules.room(spot.room()).orElseThrow().name(),
            spot.lights().stream().map(String::valueOf).collect(Collectors.joining(","))
        );
    }
}
