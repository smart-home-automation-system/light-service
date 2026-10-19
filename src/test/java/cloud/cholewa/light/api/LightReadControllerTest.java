package cloud.cholewa.light.api;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.config.ExceptionHandlerConfig;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.LightView;
import cloud.cholewa.light.model.RoomLights;
import cloud.cholewa.light.model.RoomOccupancy;
import cloud.cholewa.light.service.LightQueryService;
import cloud.cholewa.light.state.Gen1Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Import(ExceptionHandlerConfig.class)
@WebFluxTest(LightReadController.class)
class LightReadControllerTest {

    private static final LocalDateTime EVENING = LocalDateTime.of(2026, 10, 19, 19, 0, 5);
    private static final LightView LEFT_COLOR = new LightView(89, "Left color", "hall up", "shelly", "rgbw", true, 60,
        new LightView.ColorView(List.of(255, 0, 0), null), EVENING, "dashboard", true, null);
    private static final LightView STAIRS = new LightView(45, "Stairs", "stairs", "eaton", "dimmer",
        null, null, null, null, null, null, null);

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private LightQueryService lightQueryService;

    @Test
    void should_answer_every_light_by_room() {
        when(lightQueryService.all()).thenReturn(Mono.just(List.of(
            RoomLights.of("stairs", List.of(STAIRS)), RoomLights.of("hall up", List.of(LEFT_COLOR)))));

        webTestClient.get().uri("")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$[0].room").isEqualTo("stairs")
            .jsonPath("$[0].anyOn").doesNotExist()
            .jsonPath("$[1].anyOn").isEqualTo(true)
            .jsonPath("$[1].lights[0].on").isEqualTo(true)
            .jsonPath("$[1].lights[0].brightness").isEqualTo(60)
            .jsonPath("$[1].lights[0].color.rgb[0]").isEqualTo(255)
            .jsonPath("$[1].lights[0].color.white").doesNotExist()
            .jsonPath("$[1].lights[0].changedAt").isEqualTo("2026-10-19T19:00:05")
            .jsonPath("$[1].lights[0].source").isEqualTo("dashboard")
            .jsonPath("$[1].lights[0].reachable").isEqualTo(true);
    }

    //a light nothing was heard of: its fields are left out, never off or zero
    @Test
    void should_leave_out_what_is_not_known() {
        when(lightQueryService.one(45)).thenReturn(Mono.just(STAIRS));

        webTestClient.get().uri("/45")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.id").isEqualTo(45)
            .jsonPath("$.kind").isEqualTo("dimmer")
            .jsonPath("$.on").doesNotExist()
            .jsonPath("$.brightness").doesNotExist()
            .jsonPath("$.reachable").doesNotExist();
    }

    @Test
    void should_answer_a_room_as_the_registry_writes_it() {
        when(lightQueryService.inRoom(RoomName.HALL_UP)).thenReturn(Mono.just(RoomLights.of("hall up", List.of(LEFT_COLOR))));

        webTestClient.get().uri("/rooms/hall up")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.room").isEqualTo("hall up")
            .jsonPath("$.lights[0].id").isEqualTo(89);
    }

    @Test
    void should_answer_an_unknown_light_with_its_code() {
        when(lightQueryService.one(anyLong())).thenReturn(Mono.error(LightException.notFound("no light with id 99")));

        webTestClient.get().uri("/99")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_LIGHT");
    }

    @Test
    void should_take_a_gen1_action_with_204() {
        when(lightQueryService.gen1Action(90, Gen1Action.OFF)).thenReturn(Mono.empty());

        webTestClient.get().uri("/events/gen1?device=90&event=off")
            .exchange()
            .expectStatus().isNoContent();

        verify(lightQueryService).gen1Action(90, Gen1Action.OFF);
    }

    //a device configured with the wrong id: a 404, logged at WARN by the processor
    @Test
    void should_answer_a_gen1_action_of_an_unknown_device_with_404() {
        when(lightQueryService.gen1Action(anyLong(), any()))
            .thenReturn(Mono.error(LightException.notFound("no Gen1 light with id 999")));

        webTestClient.get().uri("/events/gen1?device=999&event=on")
            .exchange()
            .expectStatus().isNotFound();
    }

    //HAS-260: the occupancy of a room, alone and inside the room read; what is not known is left out
    @Test
    void should_answer_the_occupancy_of_a_room() {
        when(lightQueryService.occupancy(RoomName.OFFICE)).thenReturn(Mono.just(new RoomOccupancy("office", true, EVENING, null,
            "dark", null, List.of(new RoomOccupancy.SensorView(91, "Presence", "presence sensor", EVENING, true)))));

        webTestClient.get().uri("/rooms/office/occupancy")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.room").isEqualTo("office")
            .jsonPath("$.occupied").isEqualTo(true)
            .jsonPath("$.since").isEqualTo("2026-10-19T19:00:05")
            .jsonPath("$.lastMotionAt").doesNotExist()
            .jsonPath("$.illumination").isEqualTo("dark")
            .jsonPath("$.lux").doesNotExist()
            .jsonPath("$.sensors[0].kind").isEqualTo("presence sensor")
            .jsonPath("$.sensors[0].reachable").isEqualTo(true);
    }

    @Test
    void should_answer_a_room_with_its_occupancy_inside() {
        when(lightQueryService.inRoom(RoomName.STAIRS)).thenReturn(Mono.just(RoomLights.of("stairs", List.of(STAIRS),
            new RoomOccupancy("stairs", null, null, EVENING, null, null, List.of()))));

        webTestClient.get().uri("/rooms/stairs")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.occupancy.room").isEqualTo("stairs")
            .jsonPath("$.occupancy.occupied").doesNotExist()
            .jsonPath("$.occupancy.lastMotionAt").isEqualTo("2026-10-19T19:00:05");
    }

    //the motion_on / motion_off actions of a Shelly Motion
    @Test
    void should_take_a_gen1_motion_with_its_lux() {
        when(lightQueryService.gen1Motion(97, true, 12.5)).thenReturn(Mono.empty());
        when(lightQueryService.gen1Motion(97, false, null)).thenReturn(Mono.empty());

        webTestClient.get().uri("/events/gen1?device=97&event=motion&lux=12.5").exchange().expectStatus().isNoContent();
        webTestClient.get().uri("/events/gen1?device=97&event=motion_end").exchange().expectStatus().isNoContent();

        verify(lightQueryService).gen1Motion(97, true, 12.5);
        verify(lightQueryService).gen1Motion(97, false, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/events/gen1?device=90&event=ON", "/events/gen1?device=90&event=toggle", "/events/gen1?device=90",
        "/events/gen1?event=on", "/events/gen1?device=abc&event=on", "/rooms/attic", "/rooms/Hall up",
        "/rooms/attic/occupancy", "/events/gen1?device=90&event=on&lux=10", "/events/gen1?device=97&event=motion&lux=-1",
        "/events/gen1?device=97&event=motion&lux=dark"
    })
    void should_refuse_what_is_not_a_read_or_an_action(final String uri) {
        webTestClient.get().uri(uri)
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(lightQueryService);
    }
}
