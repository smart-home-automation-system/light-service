package cloud.cholewa.light.database.repository;

import cloud.cholewa.light.database.model.BatteryAlertEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

public interface BatteryAlertRepository extends R2dbcRepository<BatteryAlertEntity, Long> {
}
