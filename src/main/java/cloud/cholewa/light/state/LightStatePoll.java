package cloud.cholewa.light.state;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.light.driver.Gen2Profiles;
import cloud.cholewa.light.driver.Gen2Profiles.ColorComponent;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightBinding;
import cloud.cholewa.light.model.LightReading;
import cloud.cholewa.light.registry.LightRegistry;
import cloud.cholewa.shelly.http.ShellyCallException;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.Gen1DeviceStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

//The states the devices are asked for. A Gen1 light is not on MQTT (it would lose the Shelly cloud), so
///status of every one is read every minute - on or off, the brightness and the color, whatever the
//actions (out_on_url / out_off_url) did not tell. A Gen2+ light reports on MQTT by itself; its
//GetStatus every five minutes reconciles what a lost message or a restart of the pod left behind.
//Each device is asked with its own error handling, so one that does not answer neither stops the pass
//nor delays the others for long. A Gen1 light that does not answer becomes unreachable - the poll is
//all that is heard of it. A Gen2+ light does not: its reachability is what MQTT says (online, and every
//status it publishes), and one failed HTTP call next to a live MQTT stream would only make it flap - unless
//MQTT has said nothing since the start, then a failed reconciliation makes it unreachable (HAS-264).
//Nothing is retried - the next pass asks again. fixedDelay: the next pass waits for the last one to
//end; each pass is built anew (Mono.defer), Spring subscribes again to the same Mono for every run.
//Off under the test profile
@Slf4j
@Component
@RequiredArgsConstructor
public class LightStatePoll {

    //a few devices at once: a pass of devices that all time out still ends well within the minute
    private static final int CONCURRENCY = 4;
    //Bounded (HAS-302): every device call has its client's timeout, the states are written to the
    //database - a pass beyond these is hung (a dead connection, 2026-10-22) and ends there; the next
    //one asks again. Below the fixed delays
    static final Duration GEN1_LIMIT = Duration.ofSeconds(50);
    static final Duration GEN2_LIMIT = Duration.ofMinutes(2);

    private final LightRegistry lightRegistry;
    private final LightStateTracker lightStateTracker;
    private final ShellyGen1Client shellyGen1Client;
    private final ShellyRpcClient shellyRpcClient;
    private final Gen2Profiles gen2Profiles;

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT60S")
    Mono<Void> pollGen1() {
        return ScheduledPass.bounded("light Gen1 poll", GEN1_LIMIT,
            () -> pass(light -> light.binding() instanceof LightBinding.ShellyGen1, this::readGen1,
                (light, e) -> lightStateTracker.reachability(light, false, e.toString())));
    }

    @Scheduled(initialDelayString = "PT40S", fixedDelayString = "PT5M")
    Mono<Void> reconcileGen2() {
        return ScheduledPass.bounded("light Gen2 reconciliation", GEN2_LIMIT,
            () -> pass(light -> light.binding() instanceof LightBinding.ShellyGen2, this::readGen2,
                (light, e) -> Mono.fromRunnable(() -> log.info("Reconciliation of light {} ({}) failed: {}",
                        light.id(), light.name(), e.toString()))
                    .then(unknownIsUnreachable(light, e))));
    }

    //MQTT owns the reachability of a Gen2+ light - except while it has said nothing: its last will of before a
    //restart is not delivered again, so a light dead since then would stay not known for ever. Not known and not
    //answering is unreachable (HAS-264); known, it is left to MQTT, so a lost call next to a live stream does not
    //make it flap
    //a device that answered without the output it should have is not unreachable - only a call that failed
    private Mono<Void> unknownIsUnreachable(final Light light, final Throwable e) {
        if (!(e instanceof ShellyCallException)) {
            return Mono.empty();
        }
        return lightStateTracker.status(light.id())
            .filter(status -> status.reachable() == null)
            .flatMap(status -> lightStateTracker.reachability(light, false, "no MQTT word and " + e));
    }

    private Mono<Void> pass(
        final Predicate<Light> kind,
        final Function<Light, Mono<Void>> read,
        final BiFunction<Light, Throwable, Mono<Void>> failed
    ) {
        final List<Light> lights = lightRegistry.current().stream().filter(kind).toList();

        return Flux.fromIterable(lights)
            .flatMap(light -> read.apply(light)
                .onErrorResume(e -> {
                    log.debug("Light {} ({}) did not answer the status read: {}", light.id(), light.name(), e.toString());
                    return failed.apply(light, e).onErrorResume(ignored -> Mono.empty());
                }), CONCURRENCY)
            .then();
    }

    //the reading is applied apart from the read: a failed write of the state is not a device that did
    //not answer
    Mono<Void> readGen1(final Light light) {
        final LightBinding.ShellyGen1 device = (LightBinding.ShellyGen1) light.binding();

        return shellyGen1Client.status(device.address())
            .flatMap(status -> Mono.justOrEmpty(gen1Reading(light, device.channel(), status))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                    "no " + (light.kind().getValue()) + " " + device.channel() + " in the status"))))
            .flatMap(reading -> apply(light, reading));
    }

    private static Optional<LightReading> gen1Reading(final Light light, final int channel, final Gen1DeviceStatus status) {
        return switch (light.kind()) {
            case RELAY -> element(status.getRelays(), channel).flatMap(LightReadings::ofRelay);
            case DIMMER, RGBW -> element(status.getLights(), channel)
                .flatMap(gen1Light -> LightReadings.ofGen1Light(gen1Light, light.kind()));
        };
    }

    Mono<Void> readGen2(final Light light) {
        final LightBinding.ShellyGen2 device = (LightBinding.ShellyGen2) light.binding();

        final Mono<LightReading> reading = switch (light.kind()) {
            case RELAY -> shellyRpcClient.switchStatus(device.address(), device.channel())
                .flatMap(status -> Mono.justOrEmpty(LightReadings.ofSwitch(status)));
            case DIMMER -> shellyRpcClient.lightStatus(device.address(), device.channel())
                .flatMap(status -> Mono.justOrEmpty(LightReadings.ofLight(status)));
            case RGBW -> gen2Profiles.of(device)
                .flatMap(component -> component == ColorComponent.RGB
                    ? shellyRpcClient.rgbStatus(device.address(), device.channel())
                    : shellyRpcClient.rgbwStatus(device.address(), device.channel()))
                .doOnError(e -> gen2Profiles.forget(device))
                .flatMap(status -> Mono.justOrEmpty(LightReadings.ofRgbw(status)));
        };

        return reading
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("no output in the status")))
            .flatMap(read -> apply(light, read));
    }

    private Mono<Void> apply(final Light light, final LightReading reading) {
        return lightStateTracker.observe(light, reading)
            .onErrorResume(e -> {
                log.error("The state of light {} ({}) could not be stored", light.id(), light.name(), e);
                return Mono.empty();
            });
    }

    private static <T> Optional<T> element(final List<T> list, final int index) {
        return list == null || index >= list.size() ? Optional.empty() : Optional.ofNullable(list.get(index));
    }
}
