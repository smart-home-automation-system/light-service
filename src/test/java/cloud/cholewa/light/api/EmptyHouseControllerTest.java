package cloud.cholewa.light.api;

import cloud.cholewa.light.config.ExceptionHandlerConfig;
import cloud.cholewa.light.emptyhouse.EmptyHouseEngine;
import cloud.cholewa.light.emptyhouse.SimulationPlanService;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.emptyhouse.EmptyHouseStatus;
import cloud.cholewa.light.model.emptyhouse.PhaseName;
import cloud.cholewa.light.model.emptyhouse.PhasePlan;
import cloud.cholewa.light.model.emptyhouse.SimulationPlan;
import cloud.cholewa.light.model.emptyhouse.Spot;
import cloud.cholewa.light.model.emptyhouse.SpotRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//what a caller of the empty house gets (HAS-263): the status with what is not known left out, the plan both
//ways with the times to the minute, and a refusal with its code and reason
@Import(ExceptionHandlerConfig.class)
@WebFluxTest(EmptyHouseController.class)
class EmptyHouseControllerTest {

    private static final String NIGHT_BODY = """
        {"phases":[{"phase":"night","enabled":true,"from":"00:30","to":"05:00","jitterMinutes":15,
          "minStayMinutes":3,"maxStayMinutes":8,"maxSpotsAtOnce":1,"visitsMin":1,"visitsMax":2,
          "spots":[{"role":"stay","room":"bathroom up","lights":[42]},{"role":"path","room":"hall up","lights":[89,105]}]}]}
        """;
    private static final SimulationPlan NIGHT = new SimulationPlan(List.of(new PhasePlan(PhaseName.NIGHT, true,
        LocalTime.of(0, 30), LocalTime.of(5, 0), 15, 3, 8, 1, 1, 2, List.of(
            new Spot(SpotRole.STAY, "bathroom up", List.of(42L)), new Spot(SpotRole.PATH, "hall up", List.of(89L, 105L))))));

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private EmptyHouseEngine emptyHouseEngine;

    @MockitoBean
    private SimulationPlanService simulationPlanService;

    @Test
    void should_answer_the_status_and_leave_out_what_is_not_known() {
        when(emptyHouseEngine.status()).thenReturn(Mono.just(new EmptyHouseStatus(true, "unknown", null, null, false,
            LocalDateTime.of(2026, 10, 20, 17, 30), LocalDateTime.of(2026, 10, 21, 7, 0), null, List.of(), null)));

        webTestClient.get().uri("/empty-house")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.enabled").isEqualTo(true)
            .jsonPath("$.house").isEqualTo("unknown")
            .jsonPath("$.active").isEqualTo(false)
            .jsonPath("$.dusk").isEqualTo("2026-10-20T17:30:00")
            .jsonPath("$.emptySince").doesNotExist()
            .jsonPath("$.phase").doesNotExist();
    }

    @Test
    void should_read_and_answer_a_plan_with_the_times_to_the_minute() {
        when(simulationPlanService.replace(NIGHT)).thenReturn(Mono.just(NIGHT));

        webTestClient.put().uri("/empty-house/plan")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(NIGHT_BODY)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.phases[0].phase").isEqualTo("night")
            .jsonPath("$.phases[0].from").isEqualTo("00:30")
            .jsonPath("$.phases[0].spots[1].role").isEqualTo("path")
            .jsonPath("$.phases[0].spots[1].lights[1]").isEqualTo(105);
        verify(simulationPlanService).replace(NIGHT);
    }

    //a continuous phase leaves its visits out
    @Test
    void should_answer_the_plan_as_stored() {
        when(simulationPlanService.get()).thenReturn(Mono.just(new SimulationPlan(List.of(new PhasePlan(PhaseName.EVENING, true,
            LocalTime.of(16, 0), LocalTime.of(0, 30), 15, 10, 40, 2, null, null,
            List.of(new Spot(SpotRole.STAY, "kitchen", List.of(21L))))))));

        webTestClient.get().uri("/empty-house/plan")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.phases[0].to").isEqualTo("00:30")
            .jsonPath("$.phases[0].visitsMin").doesNotExist();
    }

    @Test
    void should_answer_a_refused_plan_with_its_code_and_reason() {
        when(simulationPlanService.replace(any())).thenReturn(Mono.error(LightException.invalidPlan("night: a phase needs a stay spot")));

        webTestClient.put().uri("/empty-house/plan")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(NIGHT_BODY)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("INVALID_PLAN")
            .jsonPath("$.errors[0].details").isEqualTo("night: a phase needs a stay spot");
    }

    //a phase or a role the service does not know is a body that cannot be read
    @Test
    void should_refuse_an_unknown_phase() {
        webTestClient.put().uri("/empty-house/plan")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(NIGHT_BODY.replace("\"night\"", "\"noon\""))
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(simulationPlanService);
    }
}
