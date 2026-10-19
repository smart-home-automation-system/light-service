package cloud.cholewa.light.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalTime;

//rgb is "r,g,b", as in light_state
@Table("light_motion_period")
public record MotionPeriodEntity(
    @Id
    Long id,
    String room,
    LocalTime fromTime,
    LocalTime toTime,
    Integer brightness,
    String rgb,
    Integer white
) {
}
