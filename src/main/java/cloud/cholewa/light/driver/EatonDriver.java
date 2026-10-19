package cloud.cholewa.light.driver;

import cloud.cholewa.commons.error.DownstreamErrors;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Color;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.function.Function;

//An Eaton relay or dimmer: POST /home/amx/eaton/lights/{point}?turn= and /level?value= of amx-service
//(HAS-228). Its 202 says the frame was handed to the gateway. Every refusal of amx-service - a point
//it does not know, the frame server away, a frame that may have gone out - is a failed command here,
//with its status and codes in the message for the log. An Eaton light has no colors
@Component
public class EatonDriver implements LightDriver {

    private final WebClient amxWebClient;

    public EatonDriver(@Qualifier("amxWebClient") final WebClient amxWebClient) {
        this.amxWebClient = amxWebClient;
    }

    @Override
    public Mono<Void> turn(final Light light, final boolean on) {
        final int point = point(light);
        return post(uriBuilder -> uriBuilder
            .path("/home/amx/eaton/lights/{point}")
            .queryParam("turn", on ? "on" : "off")
            .build(point));
    }

    @Override
    public Mono<Void> brightness(final Light light, final int brightness) {
        final int point = point(light);
        return post(uriBuilder -> uriBuilder
            .path("/home/amx/eaton/lights/{point}/level")
            .queryParam("value", brightness)
            .build(point));
    }

    //asks the actuator for its state (amx-service >= 2.4.0, HAS-259): its answer arrives as the usual
    //status event, not here
    public Mono<Void> requestStatus(final Light light) {
        final int point = point(light);
        return post(uriBuilder -> uriBuilder
            .path("/home/amx/eaton/lights/{point}/status")
            .build(point));
    }

    @Override
    public Mono<Void> color(final Light light, final Color color) {
        return Mono.error(() -> LightException.unsupported(light, "a color"));
    }

    private Mono<Void> post(final Function<UriBuilder, URI> uri) {
        return amxWebClient
            .post()
            .uri(uri)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> new AmxCallException(
                    "amx-service answered " + error.status().value() + " " + error.errors())))
            .toBodilessEntity()
            .then();
    }

    private static int point(final Light light) {
        return ((LightBinding.Eaton) light.binding()).point();
    }

    static class AmxCallException extends RuntimeException {

        AmxCallException(final String message) {
            super(message);
        }
    }
}
