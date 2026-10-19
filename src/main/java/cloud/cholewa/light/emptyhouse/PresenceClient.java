package cloud.cholewa.light.emptyhouse;

import cloud.cholewa.commons.error.DownstreamErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

//Where the members of the household are, from presence-service over k8s DNS - not through the gateway. What
//a failing presence-service says goes to the log; no body, or one that is not a list, is a failed call -
//never an empty household, which would read as a house nobody is in
@Slf4j
@Component
public class PresenceClient {

    private static final ParameterizedTypeReference<List<ResidentPresence>> RESIDENTS = new ParameterizedTypeReference<>() {
    };

    private final WebClient presenceWebClient;

    public PresenceClient(@Qualifier("presenceWebClient") final WebClient presenceWebClient) {
        this.presenceWebClient = presenceWebClient;
    }

    public Mono<List<ResidentPresence>> residents() {
        return presenceWebClient
            .get()
            .uri("/home/presence/residents/presence")
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("presence-service answered {} to the read of the residents: {}",
                        error.status().value(), error.errors());
                    return new IllegalStateException("presence-service answered " + error.status().value());
                }))
            .bodyToMono(RESIDENTS)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("presence-service answered without a list")));
    }
}
