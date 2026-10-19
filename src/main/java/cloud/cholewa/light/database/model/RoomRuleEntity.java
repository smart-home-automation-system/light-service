package cloud.cholewa.light.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//room is the name of a RoomName constant, the key of the row - so a rule is written by the upsert of
//RoomRuleRepository, never by save(), which takes a row with a key for an update
@Table("light_room_rule")
public record RoomRuleEntity(
    @Id
    String room,
    boolean autoOffEnabled,
    Integer autoOffDelayMin,
    boolean motionOnEnabled,
    Integer onlyBelowLux,
    Integer motionOffAfterMin,
    int manualHoldMin,
    boolean enabledByResident,
    LocalDateTime updatedAt
) {
}
