package cloud.cholewa.light.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

//room is the name of a RoomName constant; lightIds "1,2,3"
@Table("light_simulation_spot")
public record SimulationSpotEntity(
    @Id
    Long id,
    String phase,
    int position,
    String role,
    String room,
    String lightIds
) {
}
