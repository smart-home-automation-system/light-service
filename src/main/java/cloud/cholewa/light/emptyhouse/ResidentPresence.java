package cloud.cholewa.light.emptyhouse;

import java.time.LocalDateTime;

/**
 * Where an active member of the household is now, as presence-service answers it
 * ({@code GET /home/presence/residents/presence}): {@code lastCheckedAt} is the last pass that confirmed it,
 * {@code null} for a member it has never observed.
 */
public record ResidentPresence(String name, Boolean present, LocalDateTime since, LocalDateTime lastCheckedAt) {
}
