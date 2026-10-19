package cloud.cholewa.light.database.repository;

import cloud.cholewa.light.database.model.MotionPeriodEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

//the periods of a room by the time they start
public interface MotionPeriodRepository extends R2dbcRepository<MotionPeriodEntity, Long> {

    Flux<MotionPeriodEntity> findByRoomOrderByFromTime(String room);

    Flux<MotionPeriodEntity> findAllByOrderByRoomAscFromTimeAsc();

    @Modifying
    @Query("DELETE FROM light_motion_period WHERE room = :room")
    Mono<Integer> deleteByRoom(String room);
}
