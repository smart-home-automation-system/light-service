package cloud.cholewa.light.model.emptyhouse;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The empty house now (HAS-263), the answer of {@code GET /empty-house}. What is not known is left out.<br>
 * {@code enabled} - whether the service acts on an empty house; {@code house} - {@code empty}, {@code occupied}
 * or {@code unknown} (presence-service cannot be read, or has not observed every member); {@code emptySince} -
 * since when nobody is at home; {@code actingFrom} - when the service starts acting on it; {@code active} - it
 * does now, and the rules of the rooms pause.<br>
 * {@code dusk}, {@code sunrise} - the night the time belongs to; {@code phase} and {@code lit} - what the
 * presence simulation lights now (by night, whether or not the house is empty - a preview);
 * {@code nextChangeAt} - when that changes next.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmptyHouseStatus(
    boolean enabled,
    String house,
    LocalDateTime emptySince,
    LocalDateTime actingFrom,
    boolean active,
    LocalDateTime dusk,
    LocalDateTime sunrise,
    String phase,
    List<Long> lit,
    LocalDateTime nextChangeAt
) {
}
