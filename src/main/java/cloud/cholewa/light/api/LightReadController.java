package cloud.cholewa.light.api;

import cloud.cholewa.light.model.LightView;
import cloud.cholewa.light.model.RoomLights;
import cloud.cholewa.light.service.LightQueryService;
import cloud.cholewa.light.state.Gen1Action;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.List;

//The state of the lights, and the Gen1 actions that report it. The actions are a GET because that is
//what a Gen1 device calls (out_on_url, out_off_url); the gateway may repeat a GET whose connection
//broke, which is harmless - the same action twice is the same state
@RestController
@RequiredArgsConstructor
public class LightReadController {

    private final LightQueryService lightQueryService;

    @GetMapping
    Mono<List<RoomLights>> all() {
        return lightQueryService.all();
    }

    @GetMapping("/rooms/{room}")
    Mono<RoomLights> room(@PathVariable final String room) {
        return lightQueryService.inRoom(Rooms.exactly(room));
    }

    @GetMapping("/{id}")
    Mono<LightView> one(@PathVariable final long id) {
        return lightQueryService.one(id);
    }

    @GetMapping("/events/gen1")
    Mono<ResponseEntity<Void>> gen1Action(@RequestParam final long device, @RequestParam final String event) {
        final Gen1Action action = Gen1Action.ofParameter(event)
            .orElseThrow(() -> new ServerWebInputException("event must be on or off"));

        return lightQueryService.gen1Action(device, action)
            .then(Mono.fromSupplier(() -> ResponseEntity.noContent().build()));
    }
}
