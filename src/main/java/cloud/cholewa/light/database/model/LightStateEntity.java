package cloud.cholewa.light.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("light_state")
public record LightStateEntity(
    @Id
    Long id,
    long deviceId,
    boolean switchedOn,
    Integer brightness,
    String rgb,
    Integer white,
    String source,
    LocalDateTime startedAt,
    LocalDateTime lastCheckedAt
) {
}
