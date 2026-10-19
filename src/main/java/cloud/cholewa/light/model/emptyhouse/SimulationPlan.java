package cloud.cholewa.light.model.emptyhouse;

import java.util.List;

/**
 * The plan of the presence simulation (HAS-263), the body and the answer of
 * {@code GET / PUT /empty-house/plan}: its phases, at most one of each, in the order they come. No phases:
 * no simulation - an empty house only gets its lights switched off.
 */
public record SimulationPlan(List<PhasePlan> phases) {

    public static final SimulationPlan NONE = new SimulationPlan(List.of());
}
