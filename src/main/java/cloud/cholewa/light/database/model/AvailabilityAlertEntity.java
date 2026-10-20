package cloud.cholewa.light.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//device_id is the registry id and the key of the row - so a row is inserted by R2dbcEntityTemplate.insert,
//never by save(), which takes a row with a key for an update
@Table("light_availability_alert")
public record AvailabilityAlertEntity(
    @Id
    Long deviceId,
    String kind,
    String name,
    String room,
    LocalDateTime since,
    LocalDateTime lastAlertAt
) {
}
