package cloud.cholewa.light.database.repository;

import cloud.cholewa.light.database.model.SimulationSpotEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

public interface SimulationSpotRepository extends R2dbcRepository<SimulationSpotEntity, Long> {

    Flux<SimulationSpotEntity> findAllByOrderByPhaseAscPositionAsc();
}
