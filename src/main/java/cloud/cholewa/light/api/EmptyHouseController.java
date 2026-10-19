package cloud.cholewa.light.api;

import cloud.cholewa.light.emptyhouse.EmptyHouseEngine;
import cloud.cholewa.light.emptyhouse.SimulationPlanService;
import cloud.cholewa.light.model.emptyhouse.EmptyHouseStatus;
import cloud.cholewa.light.model.emptyhouse.SimulationPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

//The empty house and the plan of its presence simulation (HAS-263). The plan is written whole and answered as
//stored; no @Valid: every rule of a body is PlanRules', which answers INVALID_PLAN with the reason.
//"/empty-house" is a literal, so it is never taken for the {id} of a light
@RestController
@RequiredArgsConstructor
public class EmptyHouseController {

    private final EmptyHouseEngine emptyHouseEngine;
    private final SimulationPlanService simulationPlanService;

    @GetMapping("/empty-house")
    Mono<EmptyHouseStatus> status() {
        return emptyHouseEngine.status();
    }

    @GetMapping("/empty-house/plan")
    Mono<SimulationPlan> plan() {
        return simulationPlanService.get();
    }

    @PutMapping("/empty-house/plan")
    Mono<SimulationPlan> replace(@RequestBody final SimulationPlan plan) {
        return simulationPlanService.replace(plan);
    }
}
