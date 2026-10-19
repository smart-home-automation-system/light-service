package cloud.cholewa.light.model.emptyhouse;

import java.util.List;

/**
 * A place the simulation lights: its role, its room as the registry writes it ({@code living room}) and the
 * lights of that room it switches on together, by their registry ids.
 */
public record Spot(SpotRole role, String room, List<Long> lights) {
}
