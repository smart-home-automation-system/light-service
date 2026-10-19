package cloud.cholewa.light.api;

import cloud.cholewa.light.model.Color;
import cloud.cholewa.light.model.CommandSource;
import cloud.cholewa.light.model.RoomCommandReply;
import cloud.cholewa.light.model.Turn;
import cloud.cholewa.light.service.LightCommandService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.List;

//The commands for the lights. 202, not 200: the answer says the device took the command, never that
//the light is on. The values are taken exactly as written - a typo switches nothing.
//No @Validated on the class - WebFlux validates the constrained parameters itself, and the
//annotation would switch that off. The messages are written on the constraints and in the checks,
//so the answer reads the same on every machine
@RestController
@RequiredArgsConstructor
public class LightController {

    private static final String BRIGHTNESS_RANGE = "value must be between 1 and 100";

    private final LightCommandService lightCommandService;

    @PostMapping("/{id}")
    Mono<ResponseEntity<Void>> turn(
        @PathVariable final long id,
        @RequestParam final String turn,
        @RequestParam(defaultValue = "dashboard") final String source
    ) {
        return lightCommandService.turn(id, turn(turn), source(source))
            .then(Mono.fromSupplier(() -> ResponseEntity.accepted().build()));
    }

    @PostMapping("/{id}/brightness")
    Mono<ResponseEntity<Void>> brightness(
        @PathVariable final long id,
        @RequestParam @Min(value = 1, message = BRIGHTNESS_RANGE) @Max(value = 100, message = BRIGHTNESS_RANGE)
        final int value,
        @RequestParam(defaultValue = "dashboard") final String source
    ) {
        return lightCommandService.brightness(id, value, source(source))
            .then(Mono.fromSupplier(() -> ResponseEntity.accepted().build()));
    }

    @PostMapping("/{id}/color")
    Mono<ResponseEntity<Void>> color(
        @PathVariable final long id,
        @RequestBody final Color color,
        @RequestParam(defaultValue = "dashboard") final String source
    ) {
        return lightCommandService.color(id, checked(color), source(source))
            .then(Mono.fromSupplier(() -> ResponseEntity.accepted().build()));
    }

    @PostMapping("/rooms/{room}")
    Mono<ResponseEntity<RoomCommandReply>> turnRoom(
        @PathVariable final String room,
        @RequestParam final String turn,
        @RequestParam(defaultValue = "dashboard") final String source
    ) {
        return lightCommandService.turnRoom(Rooms.exactly(room), turn(turn), source(source))
            .map(reply -> ResponseEntity.accepted().body(reply));
    }

    private static Turn turn(final String turn) {
        return Turn.ofParameter(turn)
            .orElseThrow(() -> new ServerWebInputException("turn must be on or off"));
    }

    private static CommandSource source(final String source) {
        return CommandSource.ofParameter(source)
            .orElseThrow(() -> new ServerWebInputException("source must be dashboard, rule or empty-house"));
    }

    //nothing in the body has a default: a part left out is kept by the device, but a body with no
    //part at all would switch the light on and change nothing else - that is POST /{id}?turn=on
    private static Color checked(final Color color) {
        final List<Integer> rgb = color.rgb();
        if (rgb == null && color.white() == null && color.brightness() == null) {
            throw new ServerWebInputException("color must give rgb, white or brightness");
        }
        if (rgb != null && (rgb.size() != 3 || rgb.stream().anyMatch(value -> value == null || value < 0 || value > 255))) {
            throw new ServerWebInputException("rgb must be three values between 0 and 255");
        }
        if (color.white() != null && (color.white() < 0 || color.white() > 255)) {
            throw new ServerWebInputException("white must be between 0 and 255");
        }
        if (color.brightness() != null && (color.brightness() < 1 || color.brightness() > 100)) {
            throw new ServerWebInputException("brightness must be between 1 and 100");
        }
        return color;
    }
}
