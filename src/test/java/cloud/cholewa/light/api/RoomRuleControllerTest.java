package cloud.cholewa.light.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.config.ExceptionHandlerConfig;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.error.processor.LightExceptionProcessor;
import cloud.cholewa.light.model.rule.MotionPeriod;
import cloud.cholewa.light.model.rule.RoomRule;
import cloud.cholewa.light.model.rule.RoomRuleBody;
import cloud.cholewa.light.model.rule.RuleRun;
import cloud.cholewa.light.rule.RoomRuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//what a caller of the rules gets: the shape of a rule both ways, the times to the minute, and for a
//refusal the code, the reason and the level of the log line
@Import(ExceptionHandlerConfig.class)
@WebFluxTest(RoomRuleController.class)
class RoomRuleControllerTest {

    private static final String HALL_BODY = """
        {"autoOffEnabled":true,"autoOffDelayMinutes":10,"motionOnEnabled":true,"onlyBelowLux":30,
         "motionOffAfterMinutes":3,"manualHoldMinutes":30,
         "periods":[{"from":"22:00","to":"06:00","brightness":10,"rgb":[255,120,0]},{"from":"16:00","to":"22:00","brightness":80}]}
        """;
    private static final MotionPeriod NIGHT = new MotionPeriod(LocalTime.of(22, 0), LocalTime.of(6, 0), 10, List.of(255, 120, 0), null);
    private static final MotionPeriod EVENING = new MotionPeriod(LocalTime.of(16, 0), LocalTime.of(22, 0), 80, null, null);
    private static final RoomRuleBody HALL = new RoomRuleBody(true, 10, true, 30, 3, List.of(NIGHT, EVENING), 30);
    private static final RoomRule BEDROOM_RULE = new RoomRule("bedroom", true, 15, false, null, null, List.of(), 60, true,
        LocalDateTime.of(2026, 10, 19, 19, 30, 0, 123_456_000));
    private static final RoomRule HALL_RULE = new RoomRule("hall up", true, 10, true, 30, 3, List.of(EVENING, NIGHT), 30, false,
        LocalDateTime.of(2026, 10, 19, 20, 0));

    private final ListAppender<ILoggingEvent> processorLog = new ListAppender<>();

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private RoomRuleService roomRuleService;

    @BeforeEach
    void setUp() {
        processorLog.start();
        ((Logger) LoggerFactory.getLogger(LightExceptionProcessor.class)).addAppender(processorLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(LightExceptionProcessor.class)).detachAppender(processorLog);
    }

    //what was not given is left out, never null
    @Test
    void should_answer_every_rule() {
        when(roomRuleService.all()).thenReturn(Mono.just(List.of(BEDROOM_RULE, HALL_RULE)));

        webTestClient.get().uri("/rules")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [{"room":"bedroom","autoOffEnabled":true,"autoOffDelayMinutes":15,"motionOnEnabled":false,"periods":[],
                  "manualHoldMinutes":60,"enabledByResident":true,"updatedAt":"2026-10-19T19:30:00.123456"},
                 {"room":"hall up","autoOffEnabled":true,"autoOffDelayMinutes":10,"motionOnEnabled":true,"onlyBelowLux":30,
                  "motionOffAfterMinutes":3,"manualHoldMinutes":30,"enabledByResident":false,"updatedAt":"2026-10-19T20:00:00",
                  "periods":[{"from":"16:00","to":"22:00","brightness":80},{"from":"22:00","to":"06:00","brightness":10,"rgb":[255,120,0]}]}]
                """, true)
            .jsonPath("$[0].onlyBelowLux").doesNotExist()
            .jsonPath("$[1].periods[0].rgb").doesNotExist();
    }

    @Test
    void should_answer_the_rule_of_a_room() {
        when(roomRuleService.get(RoomName.HALL_UP)).thenReturn(Mono.just(HALL_RULE));

        webTestClient.get().uri("/rooms/hall up/rules")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.room").isEqualTo("hall up");
    }

    @Test
    void should_write_a_rule_as_given() {
        when(roomRuleService.replace(RoomName.HALL_UP, HALL)).thenReturn(Mono.just(HALL_RULE));

        webTestClient.put().uri("/rooms/hall up/rules")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(HALL_BODY)
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.enabledByResident").isEqualTo(false);

        verify(roomRuleService).replace(RoomName.HALL_UP, HALL);
    }

    //a time is to the minute, both ways
    @Test
    void should_refuse_a_time_with_seconds() {
        webTestClient.put().uri("/rooms/hall up/rules")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(HALL_BODY.replace("\"16:00\"", "\"16:00:30\""))
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(roomRuleService);
    }

    @Test
    void should_answer_a_refused_rule_with_its_reason() {
        when(roomRuleService.replace(any(), any()))
            .thenReturn(Mono.error(LightException.invalidRule("periods do not overlap: 16:00-22:00 and 21:00-23:00")));

        webTestClient.put().uri("/rooms/hall up/rules")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(HALL_BODY)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("INVALID_RULE")
            .jsonPath("$.errors[0].message").isEqualTo("Invalid room rule")
            .jsonPath("$.errors[0].details").isEqualTo("periods do not overlap: 16:00-22:00 and 21:00-23:00");

        assertThat(processorLog.list).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
    }

    @Test
    void should_answer_a_room_without_a_rule_with_its_code() {
        when(roomRuleService.get(RoomName.KITCHEN)).thenReturn(Mono.error(LightException.ruleNotFound(RoomName.KITCHEN)));

        webTestClient.get().uri("/rooms/kitchen/rules")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_RULE")
            .jsonPath("$.errors[0].details").isEqualTo("no rule for the room kitchen");
    }

    @Test
    void should_remove_a_rule() {
        when(roomRuleService.remove(RoomName.BEDROOM)).thenReturn(Mono.empty());

        webTestClient.delete().uri("/rooms/bedroom/rules")
            .exchange()
            .expectStatus().isNoContent()
            .expectBody().isEmpty();

        verify(roomRuleService).remove(RoomName.BEDROOM);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void should_switch_a_rule_by_the_resident(final boolean enabled) {
        when(roomRuleService.switchByResident(RoomName.BEDROOM, enabled)).thenReturn(Mono.just(BEDROOM_RULE));

        webTestClient.put().uri("/rooms/bedroom/rules/resident-enabled")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"enabled\":" + enabled + "}")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.room").isEqualTo("bedroom");

        verify(roomRuleService).switchByResident(RoomName.BEDROOM, enabled);
    }

    //missing is never read as off
    @Test
    void should_refuse_a_resident_switch_without_its_value() {
        webTestClient.put().uri("/rooms/bedroom/rules/resident-enabled")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(roomRuleService);
    }

    //the runs of the engine (HAS-262): what a command did not take carries its code, a taken one none
    @Test
    void should_answer_the_runs_of_a_room() {
        final LocalDateTime from = LocalDateTime.of(2026, 10, 19, 0, 0);
        final LocalDateTime to = LocalDateTime.of(2026, 10, 20, 0, 0);
        when(roomRuleService.runs(RoomName.HALL_UP, from, to)).thenReturn(Mono.just(List.of(
            new RuleRun(120, "on", "motion-on", null, LocalDateTime.of(2026, 10, 19, 20, 0, 1)),
            new RuleRun(120, "off", "motion-off", "DEVICE_FAILED", LocalDateTime.of(2026, 10, 19, 20, 4)))));

        webTestClient.get().uri("/rooms/hall up/rules/runs?from=2026-10-19T00:00:00&to=2026-10-20T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$[0].deviceId").isEqualTo(120)
            .jsonPath("$[0].action").isEqualTo("on")
            .jsonPath("$[0].reason").isEqualTo("motion-on")
            .jsonPath("$[0].at").isEqualTo("2026-10-19T20:00:01")
            .jsonPath("$[0].error").doesNotExist()
            .jsonPath("$[1].error").isEqualTo("DEVICE_FAILED");
    }

    //a week at most, to after from, both given
    @ParameterizedTest
    @ValueSource(strings = {
        "from=2026-10-12T00:00:00&to=2026-10-19T00:00:01",
        "from=2026-10-19T00:00:00&to=2026-10-19T00:00:00",
        "from=2026-10-19T00:00:00",
        "from=yesterday&to=2026-10-19T00:00:00"})
    void should_refuse_runs_over_a_span_it_does_not_answer(final String query) {
        webTestClient.get().uri("/rooms/bedroom/rules/runs?" + query)
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(roomRuleService);
    }

    @Test
    void should_answer_runs_of_a_whole_week() {
        when(roomRuleService.runs(any(), any(), any())).thenReturn(Mono.just(List.of()));

        webTestClient.get().uri("/rooms/bedroom/rules/runs?from=2026-10-12T00:00:00&to=2026-10-19T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("[]");
    }

    //the room exactly as the registry writes it
    @ParameterizedTest
    @ValueSource(strings = {"Bedroom", "hall_up", "nowhere"})
    void should_refuse_a_room_that_is_not_a_room_of_the_house(final String room) {
        webTestClient.get().uri("/rooms/{room}/rules", room)
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(roomRuleService);
    }
}
