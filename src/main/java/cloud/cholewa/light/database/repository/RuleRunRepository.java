package cloud.cholewa.light.database.repository;

import cloud.cholewa.light.database.model.RuleRunEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

public interface RuleRunRepository extends R2dbcRepository<RuleRunEntity, Long> {

    //from included, to not; in the order they were sent
    @Query("SELECT * FROM light_rule_run WHERE room = :room AND at >= :from AND at < :to ORDER BY at, id")
    Flux<RuleRunEntity> findRuns(String room, LocalDateTime from, LocalDateTime to);
}
