package cloud.cholewa.light.api;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.model.rule.ResidentSwitch;
import cloud.cholewa.light.model.rule.RoomRule;
import cloud.cholewa.light.model.rule.RoomRuleBody;
import cloud.cholewa.light.model.rule.RuleRun;
import cloud.cholewa.light.rule.RoomRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

//The rules of the rooms (HAS-261): the administrator writes a rule whole, the resident switches it off
//and on in their room. A write answers the rule as stored. No @Valid: every rule of a body is
//RoomRuleRules', which answers INVALID_RULE with the reason. "/rules" is a literal, so it is never
//taken for the {id} of a light
@RestController
@RequiredArgsConstructor
public class RoomRuleController {

    private static final Duration MAX_RUNS_SPAN = Duration.ofDays(7);

    private final RoomRuleService roomRuleService;

    @GetMapping("/rules")
    Mono<List<RoomRule>> all() {
        return roomRuleService.all();
    }

    @GetMapping("/rooms/{room}/rules")
    Mono<RoomRule> one(@PathVariable final String room) {
        return roomRuleService.get(Rooms.exactly(room));
    }

    @PutMapping("/rooms/{room}/rules")
    Mono<RoomRule> replace(@PathVariable final String room, @RequestBody final RoomRuleBody body) {
        return roomRuleService.replace(Rooms.exactly(room), body);
    }

    @DeleteMapping("/rooms/{room}/rules")
    Mono<ResponseEntity<Void>> remove(@PathVariable final String room) {
        return roomRuleService.remove(Rooms.exactly(room))
            .then(Mono.fromSupplier(() -> ResponseEntity.noContent().build()));
    }

    @PutMapping("/rooms/{room}/rules/resident-enabled")
    Mono<RoomRule> residentEnabled(@PathVariable final String room, @RequestBody final ResidentSwitch body) {
        if (body.enabled() == null) {
            throw new ServerWebInputException("enabled is missing");
        }
        return roomRuleService.switchByResident(Rooms.exactly(room), body.enabled());
    }

    //what the engine sent in the room (HAS-262), from included and to not, at most a week
    @GetMapping("/rooms/{room}/rules/runs")
    Mono<List<RuleRun>> runs(
        @PathVariable final String room,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime to
    ) {
        final RoomName name = Rooms.exactly(room);
        if (!to.isAfter(from)) {
            throw new ServerWebInputException("to is after from");
        }
        if (to.isAfter(from.plus(MAX_RUNS_SPAN))) {
            throw new ServerWebInputException("from and to are at most 7 days apart");
        }
        return roomRuleService.runs(name, from, to);
    }
}
