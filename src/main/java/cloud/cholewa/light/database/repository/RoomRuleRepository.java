package cloud.cholewa.light.database.repository;

import cloud.cholewa.light.database.model.RoomRuleEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface RoomRuleRepository extends R2dbcRepository<RoomRuleEntity, String> {

    Flux<RoomRuleEntity> findAllByOrderByRoom();

    //creates the rule of a room or replaces what the administrator writes; the switch of the resident
    //is true for a new rule and kept by a replace
    @Modifying
    @Query("""
        INSERT INTO light_room_rule (room, auto_off_enabled, auto_off_delay_min, motion_on_enabled, only_below_lux,
                                     motion_off_after_min, manual_hold_min, enabled_by_resident, updated_at)
        VALUES (:room, :autoOffEnabled, :autoOffDelayMin, :motionOnEnabled, :onlyBelowLux,
                :motionOffAfterMin, :manualHoldMin, TRUE, :updatedAt)
        ON CONFLICT (room) DO UPDATE
            SET auto_off_enabled     = EXCLUDED.auto_off_enabled,
                auto_off_delay_min   = EXCLUDED.auto_off_delay_min,
                motion_on_enabled    = EXCLUDED.motion_on_enabled,
                only_below_lux       = EXCLUDED.only_below_lux,
                motion_off_after_min = EXCLUDED.motion_off_after_min,
                manual_hold_min      = EXCLUDED.manual_hold_min,
                updated_at           = EXCLUDED.updated_at
        """)
    Mono<Integer> upsert(String room, boolean autoOffEnabled, Integer autoOffDelayMin, boolean motionOnEnabled,
                         Integer onlyBelowLux, Integer motionOffAfterMin, int manualHoldMin, LocalDateTime updatedAt);

    //answers the number of rows changed, 0 for a room without a rule
    @Modifying
    @Query("UPDATE light_room_rule SET enabled_by_resident = :enabled, updated_at = :updatedAt WHERE room = :room")
    Mono<Integer> switchByResident(String room, boolean enabled, LocalDateTime updatedAt);
}
