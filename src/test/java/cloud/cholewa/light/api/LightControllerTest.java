package cloud.cholewa.light.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.config.ExceptionHandlerConfig;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.error.processor.LightExceptionProcessor;
import cloud.cholewa.light.model.Color;
import cloud.cholewa.light.model.CommandSource;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.model.RoomCommandReply;
import cloud.cholewa.light.model.Turn;
import cloud.cholewa.light.service.LightCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//what a caller gets: the status, the body and - for a failure - the code and the level of the log
//line, which is what the alerts look at
@Import(ExceptionHandlerConfig.class)
@WebFluxTest(LightController.class)
class LightControllerTest {

    private static final Light CEILING = new Light(21, "Kitchen ceiling", RoomName.KITCHEN, SmartVendor.EATON,
        new LightBinding.Eaton(27), LightKind.RELAY);

    private final ListAppender<ILoggingEvent> processorLog = new ListAppender<>();

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private LightCommandService lightCommandService;

    @BeforeEach
    void setUp() {
        processorLog.start();
        ((Logger) LoggerFactory.getLogger(LightExceptionProcessor.class)).addAppender(processorLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(LightExceptionProcessor.class)).detachAppender(processorLog);
    }

    @ParameterizedTest
    @CsvSource({"on,ON", "off,OFF"})
    void should_accept_a_turn_for_a_light(final String parameter, final Turn turn) {
        when(lightCommandService.turn(21, turn, CommandSource.DASHBOARD)).thenReturn(Mono.empty());

        webTestClient.post().uri("/21?turn={turn}", parameter)
            .exchange()
            .expectStatus().isAccepted()
            .expectBody().isEmpty();

        verify(lightCommandService).turn(21, turn, CommandSource.DASHBOARD);
    }

    @ParameterizedTest
    @CsvSource({"dashboard,DASHBOARD", "rule,RULE", "empty-house,EMPTY_HOUSE"})
    void should_pass_who_asked_for_the_command(final String parameter, final CommandSource source) {
        when(lightCommandService.turn(21, Turn.OFF, source)).thenReturn(Mono.empty());

        webTestClient.post().uri("/21?turn=off&source={source}", parameter)
            .exchange()
            .expectStatus().isAccepted();

        verify(lightCommandService).turn(21, Turn.OFF, source);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 55, 100})
    void should_accept_a_brightness(final int value) {
        when(lightCommandService.brightness(25, value, CommandSource.DASHBOARD)).thenReturn(Mono.empty());

        webTestClient.post().uri("/25/brightness?value={value}", value)
            .exchange()
            .expectStatus().isAccepted();

        verify(lightCommandService).brightness(25, value, CommandSource.DASHBOARD);
    }

    @Test
    void should_accept_a_color() {
        final Color color = new Color(List.of(255, 128, 0), 20, 70);
        when(lightCommandService.color(71, color, CommandSource.DASHBOARD)).thenReturn(Mono.empty());

        webTestClient.post().uri("/71/color")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"rgb\":[255,128,0],\"white\":20,\"brightness\":70}")
            .exchange()
            .expectStatus().isAccepted();

        verify(lightCommandService).color(71, color, CommandSource.DASHBOARD);
    }

    //a part left out is kept by the device
    @Test
    void should_accept_a_color_with_one_part() {
        final Color color = new Color(null, null, 40);
        when(lightCommandService.color(71, color, CommandSource.RULE)).thenReturn(Mono.empty());

        webTestClient.post().uri("/71/color?source=rule")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"brightness\":40}")
            .exchange()
            .expectStatus().isAccepted();

        verify(lightCommandService).color(71, color, CommandSource.RULE);
    }

    //a typo switches nothing
    @ParameterizedTest
    @ValueSource(strings = {
        "/21?turn=ON", "/21?turn=toggle", "/21", "/21?turn=on&source=bruno", "/abc?turn=on",
        "/25/brightness?value=0", "/25/brightness?value=101", "/25/brightness", "/rooms/attic?turn=off",
        "/rooms/kitchen?turn=dim", "/rooms/Kitchen?turn=off"
    })
    void should_refuse_what_is_not_a_command(final String uri) {
        webTestClient.post().uri(uri)
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(lightCommandService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"rgb\":[255,0]}", "{\"rgb\":[255,0,0,0]}", "{\"rgb\":[256,0,0]}", "{\"rgb\":[-1,0,0]}",
        "{\"rgb\":[0,null,0]}", "{\"white\":256}", "{\"white\":-1}", "{\"brightness\":0}", "{\"brightness\":101}",
        "{\"brightness\":50.9}", "{\"rgb\":\"red\"}", "not json"
    })
    void should_refuse_what_is_not_a_color(final String body) {
        webTestClient.post().uri("/71/color")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(lightCommandService);
    }

    @Test
    void should_answer_an_unknown_light_with_its_code() {
        when(lightCommandService.turn(anyLong(), any(), any()))
            .thenReturn(Mono.error(LightException.notFound("no light with id 99")));

        webTestClient.post().uri("/99?turn=on")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_LIGHT")
            .jsonPath("$.errors[0].details").isEqualTo("no light with id 99");

        assertThat(processorLog.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
    }

    @Test
    void should_answer_a_command_the_light_cannot_take_with_its_code() {
        when(lightCommandService.brightness(anyLong(), anyInt(), any()))
            .thenReturn(Mono.error(LightException.unsupported(CEILING, "a brightness")));

        webTestClient.post().uri("/21/brightness?value=50")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("UNSUPPORTED_CAPABILITY")
            .jsonPath("$.errors[0].details").isEqualTo("light 21 (relay) cannot take a brightness");
    }

    //the status and the code alone: what the device said is in the log, at ERROR
    @Test
    void should_answer_a_failed_device_with_502_without_what_the_device_said() {
        when(lightCommandService.turn(anyLong(), any(), any())).thenReturn(Mono.error(
            LightException.deviceFailed(CEILING, new IllegalStateException("amx-service answered 502 secret-ish"))));

        webTestClient.post().uri("/21?turn=on")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("DEVICE_FAILED")
            .jsonPath("$.errors[0].details").doesNotExist()
            .consumeWith(result -> assertThat(new String(result.getResponseBodyContent())).doesNotContain("secret-ish"));

        assertThat(processorLog.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("secret-ish");
        });
    }

    @Test
    void should_answer_a_room_with_what_switched_and_what_did_not() {
        when(lightCommandService.turnRoom(RoomName.LIVING_ROOM, Turn.OFF, CommandSource.EMPTY_HOUSE))
            .thenReturn(Mono.just(new RoomCommandReply("living room", "off",
                List.of(new RoomCommandReply.LightOutcome(25, "Living room sofa", null)),
                List.of(new RoomCommandReply.LightOutcome(24, "Living room table", "DEVICE_FAILED")))));

        webTestClient.post().uri("/rooms/living room?turn=off&source=empty-house")
            .exchange()
            .expectStatus().isAccepted()
            .expectBody()
            .jsonPath("$.room").isEqualTo("living room")
            .jsonPath("$.turn").isEqualTo("off")
            .jsonPath("$.switched[0].id").isEqualTo(25)
            .jsonPath("$.switched[0].code").doesNotExist()
            .jsonPath("$.failed[0].id").isEqualTo(24)
            .jsonPath("$.failed[0].code").isEqualTo("DEVICE_FAILED");
    }
}
