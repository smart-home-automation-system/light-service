package cloud.cholewa.light.service;

import cloud.cholewa.light.config.EatonProperties;
import cloud.cholewa.light.driver.EatonDriver;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.state.LightStateTracker;
import cloud.cholewa.light.state.LightStateTracker.IssuedCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//An Eaton actuator does not always get its status through (HAS-295, the lost status of HAS-291 in the
//blinds): on 2026-10-20 the auto-off of the kitchen switched Kitchen cabinet off, the gateway took the
//telegram, and no status came - the light stayed on in the memory of this service, and the rules never
//repeat a command before the light reports a change. So after a command to an Eaton light, when no change
//was reported within confirm-after, the actuator is asked for its status once; its answer is the usual
//status event, which the tracker reads. Then, confirm-after later: an answer without the change means the
//command did not take (RuleEngine sends a command of the rules once more), no answer at all leaves the
//command unconfirmed - a WARN each, the state left as the light reports it. A request that fails is a
//WARN and nothing more. The gateway's acknowledgement of the telegram proves nothing about the actuator
@Slf4j
@Component
@RequiredArgsConstructor
public class EatonCommandConfirmation {

    private final LightStateTracker lightStateTracker;
    private final EatonDriver eatonDriver;
    private final EatonProperties eatonProperties;

    /** Starts the wait for a command that was sent to an Eaton light; nothing for any other light. */
    public void start(final Light light, final IssuedCommand command) {
        if (!(light.binding() instanceof LightBinding.Eaton)) {
            return;
        }
        confirm(light, command)
            .contextCapture()
            .subscribe(null, e -> log.error("The confirmation of light {} ({}) ended with an error",
                light.id(), light.name(), e));
    }

    Mono<Void> confirm(final Light light, final IssuedCommand command) {
        return Mono.delay(eatonProperties.confirmAfter())
            .filter(tick -> lightStateTracker.awaitingChange(light, command))
            .flatMap(tick -> requestStatus(light, command))
            .filter(Boolean::booleanValue)
            .delayElement(eatonProperties.confirmAfter())
            .doOnNext(asked -> report(light, command))
            .then();
    }

    //true when the request went out
    private Mono<Boolean> requestStatus(final Light light, final IssuedCommand command) {
        return Mono.defer(() -> {
                log.info("Light {} ({}) reported no change within {} of the command of {}, its status is asked",
                    light.id(), light.name(), eatonProperties.confirmAfter(), command.source());
                lightStateTracker.asked(command);
                return eatonDriver.requestStatus(light);
            })
            .thenReturn(true)
            .onErrorResume(e -> {
                log.warn("The status of light {} ({}) could not be asked: {}", light.id(), light.name(), e.toString());
                return Mono.just(false);
            });
    }

    private void report(final Light light, final IssuedCommand command) {
        if (lightStateTracker.notTaken(light, command)) {
            log.warn("Light {} ({}) answered the status request with its state from before the command of {}: "
                + "the command did not take", light.id(), light.name(), command.source());
        } else if (lightStateTracker.awaitingChange(light, command)) {
            log.warn("Light {} ({}) did not answer the status request within {}: the command of {} is unconfirmed, "
                + "the state left as it is", light.id(), light.name(), eatonProperties.confirmAfter(), command.source());
        }
    }
}
