package cloud.cholewa.light.model.rule;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * A command the rules engine sent to a light of the room (HAS-262), an item of
 * {@code GET /rooms/{room}/rules/runs}.<br>
 * {@code deviceId} - the light, by its id in the device registry.<br>
 * {@code action} - {@code on} or {@code off}; {@code reason} - {@code auto-off}, {@code motion-on} or
 * {@code motion-off}.<br>
 * {@code error} - the code of the error when the light did not take the command, left out when it did.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleRun(long deviceId, String action, String reason, String error, LocalDateTime at) {
}
