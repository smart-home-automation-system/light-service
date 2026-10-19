package cloud.cholewa.light.database.repository;

import cloud.cholewa.light.database.model.LightStateEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface LightStateRepository extends R2dbcRepository<LightStateEntity, Long> {

    //the latest row of every light - the state the service starts from. "Latest" is the highest id,
    //not the latest started_at: rows are inserted in the order the states changed, while started_at
    //is local time and runs backwards on the night the clocks go back
    @Query("""
        SELECT DISTINCT ON (device_id) *
        FROM light_state
        ORDER BY device_id, id DESC
        """)
    Flux<LightStateEntity> findLatestPerDevice();

    //a reading that confirms the state: moves last_checked_at of the latest row of the light and
    //writes what it knows now (a Gen1 action says only on or off, the next poll adds the
    //brightness); answers the number of rows changed, 0 when the light has no row yet
    @Modifying
    @Query("""
        UPDATE light_state
        SET last_checked_at = :checkedAt,
            brightness      = :brightness,
            rgb             = :rgb,
            white           = :white
        WHERE id = (SELECT id
                    FROM light_state
                    WHERE device_id = :deviceId
                    ORDER BY id DESC
                    LIMIT 1)
        """)
    Mono<Integer> touchLatest(long deviceId, LocalDateTime checkedAt, Integer brightness, String rgb, Integer white);
}
