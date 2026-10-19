package cloud.cholewa.light.database.repository;

import cloud.cholewa.light.database.model.SimulationPhaseEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

public interface SimulationPhaseRepository extends R2dbcRepository<SimulationPhaseEntity, String> {

    //the spots go with them (ON DELETE CASCADE)
    @Modifying
    @Query("DELETE FROM light_simulation_phase")
    Mono<Integer> deleteEveryPhase();
}
