package cloud.cholewa.light.database.repository;

import cloud.cholewa.light.database.model.AvailabilityAlertEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface AvailabilityAlertRepository extends R2dbcRepository<AvailabilityAlertEntity, Long> {

    @Modifying
    @Query("UPDATE light_availability_alert SET last_alert_at = :at WHERE device_id = :deviceId")
    Mono<Integer> reminded(long deviceId, LocalDateTime at);
}
