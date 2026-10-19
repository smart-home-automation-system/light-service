package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.emptyhouse.PhasePlan;
import cloud.cholewa.light.model.emptyhouse.SimulationPlan;
import cloud.cholewa.light.model.emptyhouse.Spot;
import cloud.cholewa.light.model.emptyhouse.SpotRole;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

//What a plan of the presence simulation has to say before it is stored (HAS-263), the way RoomRuleRules
//judges a rule: nothing has a default, a field left out is refused; the first broken rule is the answer,
//INVALID_PLAN with it in words. check() reads the body alone, checkLights() the lights against the
//registry - every light of a spot a light of the registry in the room of the spot
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PlanRules {

    static final int MAX_JITTER_MINUTES = 60;
    static final int MAX_STAY_MINUTES = 180;
    static final int MAX_SPOTS_AT_ONCE = 3;
    static final int MAX_VISITS = 6;
    static final int MAX_SPOTS = 20;
    static final int MAX_LIGHTS_OF_A_SPOT = 10;

    public static void check(final SimulationPlan plan) {
        require(plan != null && plan.phases() != null, "phases is missing - an empty list for none");
        final List<PhasePlan> phases = plan.phases();
        phases.forEach(PlanRules::checkPhase);
        require(phases.stream().map(PhasePlan::phase).distinct().count() == phases.size(),
            "phases has each phase at most once");
    }

    public static void checkLights(final SimulationPlan plan, final List<Light> lights) {
        final Map<Long, Light> byId = lights.stream().collect(Collectors.toMap(Light::id, Function.identity()));
        plan.phases().forEach(phase -> phase.spots().forEach(spot -> spot.lights().forEach(id -> {
            final Light light = byId.get(id);
            require(light != null, "light " + id + " of the " + phase.phase().value() + " is not a light of the registry");
            require(light.room().getValue().equals(spot.room()),
                "light " + id + " (" + light.name() + ") is in the " + light.room().getValue() + ", not in the " + spot.room());
        })));
    }

    private static void checkPhase(final PhasePlan phase) {
        require(phase != null, "a phase is missing");
        require(phase.phase() != null, "a phase has no name");
        final String name = phase.phase().value();
        require(phase.enabled() != null, name + ": enabled is missing");
        require(phase.from() != null && phase.to() != null, name + ": from and to are missing");
        require(!phase.from().equals(phase.to()), name + ": from and to are the same time");
        require(phase.jitterMinutes() != null && phase.jitterMinutes() >= 0 && phase.jitterMinutes() <= MAX_JITTER_MINUTES,
            name + ": jitterMinutes is between 0 and " + MAX_JITTER_MINUTES);
        require(inRange(phase.minStayMinutes(), 1, MAX_STAY_MINUTES) && inRange(phase.maxStayMinutes(), 1, MAX_STAY_MINUTES),
            name + ": minStayMinutes and maxStayMinutes are between 1 and " + MAX_STAY_MINUTES);
        require(phase.minStayMinutes() <= phase.maxStayMinutes(), name + ": minStayMinutes is not above maxStayMinutes");
        require(inRange(phase.maxSpotsAtOnce(), 1, MAX_SPOTS_AT_ONCE),
            name + ": maxSpotsAtOnce is between 1 and " + MAX_SPOTS_AT_ONCE);
        require((phase.visitsMin() == null) == (phase.visitsMax() == null),
            name + ": visitsMin and visitsMax are given together or not at all");
        if (phase.visits()) {
            require(inRange(phase.visitsMin(), 1, MAX_VISITS) && inRange(phase.visitsMax(), 1, MAX_VISITS),
                name + ": visitsMin and visitsMax are between 1 and " + MAX_VISITS);
            require(phase.visitsMin() <= phase.visitsMax(), name + ": visitsMin is not above visitsMax");
        }
        checkSpots(name, phase);
    }

    private static void checkSpots(final String name, final PhasePlan phase) {
        final List<Spot> spots = phase.spots();
        require(spots != null && !spots.isEmpty(), name + ": spots is missing or empty");
        require(spots.size() <= MAX_SPOTS, name + ": spots has at most " + MAX_SPOTS + " spots");
        spots.forEach(spot -> {
            require(spot != null && spot.role() != null, name + ": a spot has no role");
            require(spot.room() != null && room(spot.room()).isPresent(), name + ": " + spot.room() + " is not a room of the house");
            require(spot.lights() != null && !spot.lights().isEmpty() && spot.lights().size() <= MAX_LIGHTS_OF_A_SPOT,
                name + ": a spot has 1 to " + MAX_LIGHTS_OF_A_SPOT + " lights");
            require(spot.lights().stream().allMatch(id -> id != null && id > 0), name + ": a light of a spot is a registry id");
            require(new HashSet<>(spot.lights()).size() == spot.lights().size(), name + ": a light is twice in a spot");
        });
        require(spots.stream().anyMatch(spot -> spot.role() == SpotRole.STAY), name + ": a phase needs a stay spot");
        require(!phase.visits() || spots.stream().noneMatch(spot -> spot.role() == SpotRole.CLOSING),
            name + ": a closing spot belongs to a continuous phase, not to visits");
    }

    //the value of the room exactly as the registry writes it
    static Optional<RoomName> room(final String value) {
        return Arrays.stream(RoomName.values()).filter(room -> room.getValue().equals(value)).findFirst();
    }

    private static boolean inRange(final Integer value, final int min, final int max) {
        return value != null && value >= min && value <= max;
    }

    private static void require(final boolean rule, final String reason) {
        if (!rule) {
            throw LightException.invalidPlan(reason);
        }
    }
}
