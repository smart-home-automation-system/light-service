package cloud.cholewa.light.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A BLU Motion warned about for a low battery (HAS-530). device_id is the registry id and the key of the row - so a
//row is inserted by R2dbcEntityTemplate.insert, never by save(), which takes a row with a key for an update
@Table("light_battery_alert")
public record BatteryAlertEntity(
    @Id
    Long deviceId,
    String name,
    String room,
    Integer level,
    LocalDateTime since
) {
}
