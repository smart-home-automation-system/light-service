package cloud.cholewa.light.service;

import cloud.cholewa.commons.error.model.ErrorId;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.driver.LightDriver;
import cloud.cholewa.light.driver.LightDrivers;
import cloud.cholewa.light.error.LightError;
import cloud.cholewa.light.error.LightException;
import cloud.cholewa.light.model.Color;
import cloud.cholewa.light.model.CommandSource;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.RoomCommandReply;
import cloud.cholewa.light.model.RoomCommandReply.LightOutcome;
import cloud.cholewa.light.model.Turn;
import cloud.cholewa.light.registry.LightRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

//The commands for the lights. Every one is checked against what the light can do, logged with who
//asked for it, and sent once - nothing is retried. A command writes no state: whether a light is on
//comes only from what the devices report (HAS-259). Whatever a driver fails with becomes
//DEVICE_FAILED, a 502, with the reason in the log
@Slf4j
@Service
@RequiredArgsConstructor
public class LightCommandService {

    private final LightRegistry lightRegistry;
    private final LightDrivers lightDrivers;

    public Mono<Void> turn(final long id, final Turn turn, final CommandSource source) {
        return lightRegistry.find(id).flatMap(light -> turn(light, turn, source));
    }

    public Mono<Void> brightness(final long id, final int brightness, final CommandSource source) {
        return lightRegistry.find(id).flatMap(light -> light.dims()
            ? execute(light, source, "brightness " + brightness, driver -> driver.brightness(light, brightness))
            : Mono.error(LightException.unsupported(light, "a brightness")));
    }

    public Mono<Void> color(final long id, final Color color, final CommandSource source) {
        return lightRegistry.find(id).flatMap(light -> light.colors()
            ? execute(light, source, "color " + color, driver -> driver.color(light, color))
            : Mono.error(LightException.unsupported(light, "a color")));
    }

    //One light after the other, in the order of the registry: the Eaton lights of a room share a
    //gateway, and the frames go out one by one anyway. One that fails is skipped and named - the
    //others still switch. When none took the command there is nothing to accept: the room fails like
    //a single light would
    public Mono<RoomCommandReply> turnRoom(final RoomName room, final Turn turn, final CommandSource source) {
        return lightRegistry.inRoom(room)
            .flatMapMany(Flux::fromIterable)
            .concatMap(light -> turn(light, turn, source)
                .thenReturn(new Outcome(light, null))
                //execute() leaves nothing but a LightException
                .onErrorResume(LightException.class, e -> {
                    if (e.getHttpStatus().is5xxServerError()) {
                        log.error("Light {} of the room {} skipped: {}", light.id(), room.getValue(), e.getMessage());
                    } else {
                        log.warn("Light {} of the room {} skipped: {}", light.id(), room.getValue(), e.getMessage());
                    }
                    return Mono.just(new Outcome(light, e));
                }))
            .collectList()
            .flatMap(outcomes -> outcomes.stream().anyMatch(outcome -> outcome.failure() == null)
                ? Mono.just(new RoomCommandReply(
                    room.getValue(),
                    turn.value(),
                    outcomes.stream().filter(outcome -> outcome.failure() == null).map(Outcome::toReply).toList(),
                    outcomes.stream().filter(outcome -> outcome.failure() != null).map(Outcome::toReply).toList()))
                : Mono.error(nothingSwitched(room, outcomes)));
    }

    private static LightException nothingSwitched(final RoomName room, final List<Outcome> outcomes) {
        final LightException first = outcomes.stream()
            .map(Outcome::failure)
            .filter(failure -> failure.getError() == LightError.DEVICE_FAILED)
            .findFirst()
            .orElse(outcomes.getFirst().failure());

        return new LightException(first.getHttpStatus(), first.getError(),
            "no light of the room " + room.getValue() + " took the command");
    }

    private Mono<Void> turn(final Light light, final Turn turn, final CommandSource source) {
        return execute(light, source, "turn " + turn.value(), driver -> driver.turn(light, turn.on()));
    }

    private Mono<Void> execute(
        final Light light,
        final CommandSource source,
        final String what,
        final Function<LightDriver, Mono<Void>> call
    ) {
        return Mono.defer(() -> {
                log.info("Light {} ({}, {}) asked to {} by {}",
                    light.id(), light.name(), light.room().getValue(), what, source.value());
                return call.apply(lightDrivers.of(light));
            })
            .onErrorMap(e -> !(e instanceof LightException), e -> LightException.deviceFailed(light, e));
    }

    private record Outcome(Light light, LightException failure) {

        private LightOutcome toReply() {
            return new LightOutcome(light.id(), light.name(), failure == null ? null : ErrorId.codeOf(failure.getError()));
        }
    }
}
