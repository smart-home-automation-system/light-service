package cloud.cholewa.light.driver;

import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.light.error.LightError;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Color;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EatonDriverTest {

    private static final Light SOFA = new Light(25, "Living room sofa", RoomName.LIVING_ROOM, SmartVendor.EATON,
        new LightBinding.Eaton(24, EatonGatewayType.LIGHTS), LightKind.DIMMER);

    private MockWebServer server;
    private EatonDriver driver;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        driver = new EatonDriver(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @ParameterizedTest
    @CsvSource({"true,on", "false,off"})
    void should_switch_the_point_through_amx_service(final boolean on, final String turn) throws InterruptedException {
        server.enqueue(accepted());

        driver.turn(SOFA, on).as(StepVerifier::create).verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/amx/eaton/lights/24");
        assertThat(request.getUrl().queryParameter("turn")).isEqualTo(turn);
    }

    @Test
    void should_set_the_level_of_a_dimmer() throws InterruptedException {
        server.enqueue(accepted());

        driver.brightness(SOFA, 40).as(StepVerifier::create).verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/amx/eaton/lights/24/level");
        assertThat(request.getUrl().queryParameter("value")).isEqualTo("40");
    }

    //HAS-259: the answer arrives as a status event, the call only says the request went out
    @Test
    void should_ask_the_actuator_for_its_status_through_amx_service() throws InterruptedException {
        server.enqueue(accepted());

        driver.requestStatus(SOFA).as(StepVerifier::create).verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/amx/eaton/lights/24/status");
    }

    //the frame may have gone out or not - either way the command is failed, and the codes go to the log
    @Test
    void should_fail_with_the_status_and_the_codes_of_amx_service() {
        server.enqueue(new MockResponse.Builder().code(502)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("{\"errors\":[{\"message\":\"AMX frame server unavailable\",\"code\":\"FRAME_SERVER_UNAVAILABLE\"}]}")
            .build());

        driver.turn(SOFA, true).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(e)
                .isInstanceOf(EatonDriver.AmxCallException.class)
                .hasMessageContaining("amx-service answered 502")
                .hasMessageContaining("FRAME_SERVER_UNAVAILABLE"))
            .verify();
    }

    @Test
    void should_not_send_a_color_to_an_eaton_light() {
        driver.color(SOFA, new Color(List.of(255, 0, 0), null, null)).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((LightException) e).getError()).isEqualTo(LightError.UNSUPPORTED_CAPABILITY))
            .verify();

        assertThat(server.getRequestCount()).isZero();
    }

    private static MockResponse accepted() {
        return new MockResponse.Builder().code(202)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("{\"sentAt\":\"2026-10-19T20:06:44\"}").build();
    }
}
