package cloud.cholewa.light.state;

import cloud.cholewa.light.database.RgbColumn;
import cloud.cholewa.light.database.model.LightStateEntity;
import cloud.cholewa.light.database.repository.LightStateRepository;
import cloud.cholewa.light.model.CommandSource;
import cloud.cholewa.light.model.Light;
import cloud.cholewa.light.model.LightReading;
import cloud.cholewa.light.model.LightStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

//The real state of every light, whoever switched it - a wall switch, the Shelly app, an AMX panel or
//this service: what the devices report (LightEventRouter, LightStatePoll, the Gen1 actions) is merged
//here into the state kept in memory, and a change is stored - a row only when the light is switched
//or its brightness or color changes, a reading that confirms it moves last_checked_at of the latest
//row (the presence-service shape). At the start the latest rows are read back once, before the first
//reading is applied; a database that cannot be read then is a WARN, and the state is rebuilt from the
//devices (the Gen1 poll within a minute, MQTT within a minute, the Eaton status requests at once).
//
//The memory is the truth while the pod runs; a write that fails leaves the database one change
//behind, which the next restart reads back and the next readings correct. Readings of one light from
//two channels at once (a Gen1 action and the poll) are merged one after the other, and their rows are
//written in that order
@Slf4j
@Component
public class LightStateTracker {

    //a change seen this long after a command of this service is that command's. An Eaton actuator
    //reports within a second or two, MQTT at once; a Gen1 light without actions only at the next poll,
    //and two polls lie more than their 60 s apart (fixedDelay counts from the end of a pass) - so twice that
    static final Duration COMMAND_WINDOW = Duration.ofMinutes(2);
    static final String EXTERNAL = "external";

    //the width of the column: the word of a device is not trusted to be short
    private static final int SOURCE_LENGTH = 50;

    private final LightStateRepository repository;
    private final Clock clock;

    private final Map<Long, LightStatus> statuses = new ConcurrentHashMap<>();
    private final Map<Long, IssuedCommand> commands = new ConcurrentHashMap<>();
    //the end of the last write of every light: the next one waits for it, so the rows of one light are
    //inserted in the order the states changed - the latest row (the highest id) is what a restart reads
    //back. Only the last one is held, never a chain of them
    private final Map<Long, Sinks.Empty<Void>> writes = new ConcurrentHashMap<>();
    private final Mono<Void> restored;

    public LightStateTracker(final LightStateRepository repository, final Clock clock) {
        this.repository = repository;
        this.clock = clock;
        //cached: read once, by whatever needs the state first; never repeated, also after a failure
        this.restored = Flux.defer(repository::findLatestPerDevice)
            .doOnNext(row -> statuses.putIfAbsent(row.deviceId(), new LightStatus(
                row.switchedOn(), row.brightness(), RgbColumn.values(row.rgb()), row.white(), row.startedAt(), row.source(), null)))
            .count()
            .doOnNext(count -> log.info("State of {} lights restored from the database", count))
            .then()
            .onErrorResume(e -> {
                log.warn("The state of the lights could not be restored, it is rebuilt from the devices: {}",
                    e.toString());
                return Mono.empty();
            })
            .cache();
    }

    /** The state of one light; {@link LightStatus#unknown()} for one nothing was heard of. */
    public Mono<LightStatus> status(final long id) {
        return restored.then(Mono.fromSupplier(() -> statuses.getOrDefault(id, LightStatus.unknown())));
    }

    /**
     * A command of this service is about to go out: the change it makes is attributed to its source.
     * Noted before it is sent - a device may report the change before it answers - and kept when the
     * call fails: a command that timed out may have switched the light all the same.
     *
     * @return the command, for {@link #awaitingChange}, {@link #asked} and {@link #notTaken} - a newer
     * command for the same light replaces it
     */
    public IssuedCommand commanded(final Light light, final CommandSource source) {
        final IssuedCommand command = new IssuedCommand(source.value(), Instant.now(clock));
        commands.put(light.id(), command);
        return command;
    }

    /** Whether the command is still the light's and waits for its change: no change reported, not replaced. */
    public boolean awaitingChange(final Light light, final IssuedCommand command) {
        return pendingCommand(light.id()) == command;
    }

    /**
     * The actuator is about to be asked for its status because of the command (HAS-295): a reading that
     * then confirms the state from before means the command did not take. Noted before the request goes
     * out - the answer may arrive before amx-service answers the request.
     */
    public void asked(final IssuedCommand command) {
        command.asked.set(true);
    }

    /** The status request of the command could not be sent: a later confirmation answers nobody. */
    public void askFailed(final IssuedCommand command) {
        command.asked.set(false);
    }

    /** The command is still the light's, and the light answered the status request without its change. */
    public boolean notTaken(final Light light, final IssuedCommand command) {
        return awaitingChange(light, command) && command.notTaken.get();
    }

    /** The light's command is one of the source, and the light answered the status request without its change. */
    public boolean notTaken(final long id, final CommandSource source) {
        final IssuedCommand command = pendingCommand(id);
        return command != null && command.source().equals(source.value()) && command.notTaken.get();
    }

    public Mono<Void> observe(final Light light, final LightReading reading) {
        return restored.then(Mono.defer(() -> {
            //cut to what the column keeps: a restart must read back the same time
            final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
            final IssuedCommand command = pendingCommand(light.id());
            final AtomicReference<LightStatus> before = new AtomicReference<>();
            final AtomicReference<Decision> decision = new AtomicReference<>();
            final Sinks.Empty<Void> done = Sinks.empty();
            final AtomicReference<Sinks.Empty<Void>> last = new AtomicReference<>();

            //the merge and the place of its write in the queue of the light are taken together
            statuses.compute(light.id(), (id, previous) -> {
                final LightStatus known = previous == null ? LightStatus.unknown() : previous;
                before.set(known);
                decision.set(decide(known, reading, now, command == null ? null : command.source()));
                last.set(writes.put(id, done));
                return decision.get().status();
            });

            if (decision.get().changed() && command != null) {
                commands.remove(light.id(), command);
            } else if (command != null && command.asked.get()) {
                //the answer to the status request of HAS-295: the state from before the command
                command.notTaken.set(true);
            }
            if (Boolean.FALSE.equals(before.get().reachable())) {
                log.info("Light {} ({}) is reachable again", light.id(), light.name());
            }
            //every caller subscribes to what it gets; done is signalled however the write ends, a
            //cancelled one included, so one write that failed holds up none after it
            return (last.get() == null ? Mono.<Void>empty() : last.get().asMono())
                .then(Mono.defer(() -> write(light, decision.get(), now)))
                .doFinally(signal -> done.tryEmitEmpty());
        }));
    }

    /** Reachability alone, without a state: the MQTT last will of a device, a poll that failed. */
    public Mono<Void> reachability(final Light light, final boolean reachable, final String reason) {
        return restored.then(Mono.fromRunnable(() -> {
            final AtomicReference<LightStatus> before = new AtomicReference<>();
            statuses.compute(light.id(), (id, previous) -> {
                final LightStatus known = previous == null ? LightStatus.unknown() : previous;
                before.set(known);
                return known.withReachable(reachable);
            });

            if (Boolean.valueOf(reachable).equals(before.get().reachable())) {
                return;
            }
            if (!reachable) {
                log.warn("Light {} ({}) is not reachable: {}", light.id(), light.name(), reason);
            } else if (before.get().reachable() != null) {
                log.info("Light {} ({}) is reachable again", light.id(), light.name());
            }
        }));
    }

    //Merges one reading into the state known before it. Switched on or off is a change; so is a
    //brightness or a color that differs from the one known - a light dimmed from a wall button, or
    //while off, the level it will come back at. What the reading does not say is kept from before: an
    //Eaton dimmer that reports 0 keeps its last brightness, a Gen1 action its brightness and color.
    //The rest confirms what is known and fills in what the reading adds
    static Decision decide(
        final LightStatus previous,
        final LightReading reading,
        final LocalDateTime now,
        final String command
    ) {
        final Integer brightness = reading.brightness() != null ? reading.brightness() : previous.brightness();
        final List<Integer> rgb = reading.rgb() != null ? reading.rgb() : previous.rgb();
        final Integer white = reading.white() != null ? reading.white() : previous.white();

        final boolean changed = !Boolean.valueOf(reading.on()).equals(previous.on())
            || differs(reading.brightness(), previous.brightness())
            || differs(reading.rgb(), previous.rgb())
            || differs(reading.white(), previous.white());

        if (changed) {
            return new Decision(new LightStatus(reading.on(), brightness, rgb, white, now,
                command != null ? command : deviceSource(reading), true), true);
        }
        return new Decision(new LightStatus(reading.on(), brightness, rgb, white, previous.changedAt(),
            previous.source(), true), false);
    }

    //a value the reading did not say, or one never known before, is no difference
    private static boolean differs(final Object reading, final Object previous) {
        return reading != null && previous != null && !Objects.equals(reading, previous);
    }

    private static String deviceSource(final LightReading reading) {
        final String source = reading.deviceSource();
        if (source == null || source.isBlank()) {
            return EXTERNAL;
        }
        return source.length() > SOURCE_LENGTH ? source.substring(0, SOURCE_LENGTH) : source;
    }

    private IssuedCommand pendingCommand(final long id) {
        final IssuedCommand command = commands.get(id);
        if (command == null) {
            return null;
        }
        if (command.at().plus(COMMAND_WINDOW).isBefore(Instant.now(clock))) {
            commands.remove(id, command);
            return null;
        }
        return command;
    }

    private Mono<Void> write(final Light light, final Decision decision, final LocalDateTime now) {
        final LightStatus status = decision.status();

        if (decision.changed()) {
            return repository.save(entity(light, status, now))
                .doOnNext(saved -> log.info("Light {} ({}) is {}{}{} since {}, switched by {}",
                    light.id(), light.name(), status.on() ? "on" : "off",
                    status.brightness() == null ? "" : " at " + status.brightness(),
                    status.rgb() == null ? "" : " in " + status.rgb(),
                    now, status.source()))
                .then();
        }

        return repository.touchLatest(light.id(), now, status.brightness(), RgbColumn.text(status.rgb()), status.white())
            .filter(updated -> updated == 0)
            //the memory knows a state the table has no row for (rows removed by hand)
            .flatMap(nothingUpdated -> {
                log.warn("No state row to confirm for light {} ({}), opening a new one", light.id(), light.name());
                return repository.save(entity(light, status, now));
            })
            .then();
    }

    private static LightStateEntity entity(final Light light, final LightStatus status, final LocalDateTime now) {
        return new LightStateEntity(null, light.id(), status.on(), status.brightness(), RgbColumn.text(status.rgb()),
            status.white(), status.source(), status.changedAt(), now);
    }

    record Decision(LightStatus status, boolean changed) {
    }

    /**
     * A command of this service that is still waiting for the change it makes. Compared by identity: a
     * newer command for the same light is another one, even with the same source in the same instant.
     */
    public static final class IssuedCommand {

        private final String source;
        private final Instant at;
        private final AtomicBoolean asked = new AtomicBoolean();
        private final AtomicBoolean notTaken = new AtomicBoolean();

        private IssuedCommand(final String source, final Instant at) {
            this.source = source;
            this.at = at;
        }

        public String source() {
            return source;
        }

        Instant at() {
            return at;
        }
    }
}
