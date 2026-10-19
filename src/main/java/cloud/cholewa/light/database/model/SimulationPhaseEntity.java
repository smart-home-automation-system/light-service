package cloud.cholewa.light.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.time.LocalTime;

//phase is the name of a PhaseName constant, the key of the row - so a phase is inserted by
//R2dbcEntityTemplate.insert, never by save(), which takes a row with a key for an update
@Table("light_simulation_phase")
public record SimulationPhaseEntity(
    @Id
    String phase,
    boolean enabled,
    LocalTime fromTime,
    LocalTime toTime,
    int jitterMin,
    int minStayMin,
    int maxStayMin,
    int maxSpots,
    Integer visitsMin,
    Integer visitsMax,
    LocalDateTime updatedAt
) {
}
