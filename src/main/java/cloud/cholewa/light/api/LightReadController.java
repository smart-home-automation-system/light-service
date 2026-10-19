package cloud.cholewa.light.api;

import cloud.cholewa.light.model.LightView;
import cloud.cholewa.light.model.RoomLights;
import cloud.cholewa.light.model.RoomOccupancy;
import cloud.cholewa.light.service.LightQueryService;
import cloud.cholewa.light.state.Gen1Action;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.List;

//The state of the lights and the occupancy of the rooms, and the Gen1 actions that report them. The
//actions are a GET because that is what a Gen1 device calls (out_on_url, out_off_url, motion_on,
//motion_off); the gateway may repeat a GET whose connection broke, which is harmless - the same action
//twice is the same state
@RestController
@RequiredArgsConstructor
public class LightReadController {

    private static final String MOTION = "motion";
    private static final String MOTION_END = "motion_end";

    private final LightQueryService lightQueryService;

    @GetMapping
    Mono<List<RoomLights>> all() {
        return lightQueryService.all();
    }

    @GetMapping("/rooms/{room}")
    Mono<RoomLights> room(@PathVariable final String room) {
        return lightQueryService.inRoom(Rooms.exactly(room));
    }

    @GetMapping("/rooms/{room}/occupancy")
    Mono<RoomOccupancy> occupancy(@PathVariable final String room) {
        return lightQueryService.occupancy(Rooms.exactly(room));
    }

    @GetMapping("/{id}")
    Mono<LightView> one(@PathVariable final long id) {
        return lightQueryService.one(id);
    }

    //on / off of a Gen1 light (HAS-259); motion / motion_end of a Shelly Motion (HAS-260), with the lux
    //of the moment when its URL carries one - a lux is a sensor's, never a light's
    @GetMapping("/events/gen1")
    Mono<ResponseEntity<Void>> gen1Action(
        @RequestParam final long device,
        @RequestParam final String event,
        @RequestParam(required = false) @DecimalMin("0") @DecimalMax("200000") final Double lux
    ) {
        final Mono<Void> handled;
        if (MOTION.equals(event) || MOTION_END.equals(event)) {
            handled = lightQueryService.gen1Motion(device, MOTION.equals(event), lux);
        } else {
            final Gen1Action action = Gen1Action.ofParameter(event)
                .orElseThrow(() -> new ServerWebInputException("event must be on, off, motion or motion_end"));
            if (lux != null) {
                throw new ServerWebInputException("lux belongs to a motion event");
            }
            handled = lightQueryService.gen1Action(device, action);
        }
        return handled.then(Mono.fromSupplier(() -> ResponseEntity.noContent().build()));
    }
}
