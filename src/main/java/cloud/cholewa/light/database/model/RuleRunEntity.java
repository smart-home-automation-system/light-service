package cloud.cholewa.light.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//room is the name of a RoomName constant; error the code of a LightError, null for a command the light took
@Table("light_rule_run")
public record RuleRunEntity(
    @Id
    Long id,
    String room,
    long deviceId,
    String action,
    String reason,
    String error,
    LocalDateTime at
) {
}
