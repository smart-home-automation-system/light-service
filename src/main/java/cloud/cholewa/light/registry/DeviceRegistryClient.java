package cloud.cholewa.light.registry;

import cloud.cholewa.commons.error.DownstreamErrors;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.SmartDevice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

//The switched-on devices of one kind of the device registry of database-service: the lights, and since
//HAS-260 the presence and motion sensors and the BTHome gateways that deliver the Bluetooth ones. What a
//failing database-service says goes to the log, never further; no body at all, or one that is not a
//list, is a failed call - never an empty registry, which would make every device unknown
@Slf4j
@Component
public class DeviceRegistryClient {

    private static final ParameterizedTypeReference<List<SmartDevice>> DEVICES = new ParameterizedTypeReference<>() {
    };

    private final WebClient databaseWebClient;

    public DeviceRegistryClient(@Qualifier("databaseWebClient") final WebClient databaseWebClient) {
        this.databaseWebClient = databaseWebClient;
    }

    public Mono<List<SmartDevice>> getLights() {
        return devices(DeviceKind.LIGHT);
    }

    public Mono<List<SmartDevice>> getPresenceSensors() {
        return devices(DeviceKind.PRESENCE_SENSOR);
    }

    public Mono<List<SmartDevice>> getMotionSensors() {
        return devices(DeviceKind.MOTION_SENSOR);
    }

    public Mono<List<SmartDevice>> getBthomeGateways() {
        return devices(DeviceKind.BTHOME_GATEWAY);
    }

    private Mono<List<SmartDevice>> devices(final DeviceKind kind) {
        return databaseWebClient
            .get()
            .uri(uriBuilder -> uriBuilder
                .path("/home/device/registry")
                .queryParam("kind", kind.getValue())
                .queryParam("enabled", true)
                .build()
            )
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("database-service answered {} to the read of the devices of the kind {}: {}",
                        error.status().value(), kind.getValue(), error.errors());
                    return new RegistryCallException("database-service answered " + error.status().value());
                }))
            .bodyToMono(DEVICES)
            .switchIfEmpty(Mono.error(() -> new RegistryCallException("database-service answered without a list")));
    }
}
