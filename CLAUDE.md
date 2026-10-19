# light-service

The lights of the house (epic HAS-256): switching the Eaton and Shelly lights, knowing whether
they are really on and switching them by the rules of each room - motion, presence and an empty
house. Since HAS-257 it consumes the device events; since HAS-258 it knows the lights from the
device registry and switches them on command through three drivers (`POST /home/light/...`);
since HAS-259 it tracks the real state of every light from what the devices report and serves it
(`GET /home/light`, `/rooms/{room}`, `/{id}`); since HAS-260 it knows from the presence and motion
sensors whether a room is occupied, when it last saw motion and how light it is (`GET
/home/light/rooms/{room}/occupancy`, and the same object inside the room read); since HAS-261 it stores
the rules of each room (`/home/light/rules`, `/rooms/{room}/rules`); since HAS-262 the engine runs them -
auto-off, motion-on, motion-off and the manual hold - and logs every command it sends
(`GET /home/light/rooms/{room}/rules/runs`); since HAS-263 it acts on an empty house - the lights left
on by day switched off, a presence simulation by night (`/home/light/empty-house`, its plan in the database).

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The owner delegated the lighting epic:
Claude implements its tasks here ("zajmij się HAS-n"), as in blinds-service and weather-service.

## Role in the system

- Talks to: RabbitMQ — consumes `device.prod.light` on `/device` (user `device`), publishes
  notifications to `/notification` (user `notification`, a second connection); calls
  `database-service` (device registry), `amx-service` (the Eaton lights) and `presence-service`
  (who is at home) over k8s DNS (`internal.service.*`). Everything is routed by `api-gateway-service`
  >= 0.8.0 (`/light`, `/light/**`, every method, HAS-265); the dashboard will branch on the codes.
- Uses libraries: `cholewa-commons` 1.9.0 (pool, error contract, `DownstreamErrors`, connection
  names, `NotificationPublisher`, `cholewa.required-properties`), `smart-home-sdk` 1.12.0
  (`SmartDevice` and `LightKind` of the registry; a zone as the channel of a presence sensor), `shelly-client` 2.1.0 (`ShellyGen1Client`,
  `ShellyRpcClient`; `ShellyHttpClientCustomizer` puts their calls into the log).
- Database: own database `home-automation-light`, pool `database.pool.max-size: 2` (heating 2 /
  database 4 / water 2 / presence 2 / blinds 2 / weather 2 / light 2 = 16 of 22), pinned by
  `LightServiceApplicationTest`. Tables: `light_state` (`V1`, HAS-259), `light_room_rule` and
  `light_motion_period` (`V2`, HAS-261), `light_rule_run` (`V3`, HAS-262 - its Jira text says `V4`),
  `light_simulation_phase` and `light_simulation_spot` (`V4`, HAS-263). **The next migration is `V5`**.
- Integrates with hardware: the Eaton lights through `amx-service`, the Shelly lights over HTTP
  (commands) and MQTT (status topics) - from HAS-258 on.

## Build & run

- Build + tests: `mvn verify` **needs a running Docker** since HAS-259: `LightStateRepositoryTest`
  runs the `DISTINCT ON` and the `touchLatest` of the state, `RoomRuleServiceTest` the upsert of the
  rules and the log of the runs, on a Testcontainers PostgreSQL (`@DataR2dbcTest`, migrations applied by hand). Without Docker it fails, it is not skipped.
- Local run: `home,local` Spring profiles, port `6012` (Actuator `8012`); in-cluster port
  `6200`, Actuator `8200`. Locally the broker is `localhost`, the services are on 6005 / 6001 /
  6009, and the broker passwords default to a dummy. The overall health is `DOWN` without a
  broker; readiness and liveness stay `UP`.

## Specifics

- **The queue gets every event of every device** (bound with `#`, about 130 a minute): Shelly
  status topics (routing key `<id>.status.<component>`) and Eaton `DeviceEvent`s from
  `amx-service` (`eaton.<gateway>.<point>`). The service has to filter by the device registry
  before it acts - from memory, never a registry read per event (blinds-service:
  `BlindRegistry.current()`). `DeviceEventConsumer` takes the raw `Message` (no converter: the
  two kinds of body differ). Manual ack, prefetch 3, `.contextCapture()` last, errors logged
  and completed — a `Mono` that errors is redelivered at once.
- **The lights are the registry's, read every five minutes** (`LightRegistry`,
  `LightRegistryRefresh`): a failed read keeps the last list (WARN); until one read has
  succeeded a lookup reads the registry itself and answers `REGISTRY_UNAVAILABLE` (502), never
  `NOT_FOUND_LIGHT`. `LightMapper` skips what it cannot drive - **a light without `lightKind`
  too**: its capabilities would be a guess (on 2026-10-19 the 18 Eaton lights of the registry all
  had one; the Shelly lights come with HAS-269).
- **What a light can do is its `lightKind`** (`Light.dims()`, `Light.colors()`): relay, dimmer
  (+ brightness), rgbw (+ color). The service refuses the rest with `UNSUPPORTED_CAPABILITY`
  before a driver is called; amx-service applies the same rule to a level.
- **The drivers** (`LightDrivers`, by the sealed `LightBinding`):
  - Gen1: relay `/relay`, dimmer `/light` (a dimmer, a Duo, a bulb or an RGBW2 in the mode
    white - all three RGBW2 of the house were in it on 2026-10-11), rgbw `/color` - **this mode
    has no brightness, the brightness is sent as the gain**.
  - Gen2+: relay `Switch.Set`, dimmer `Light.Set`, rgbw **`RGB.Set` or `RGBW.Set` by the profile
    of the device**, not the registry: both Plus RGBW PM of the house (HALL UP LEFT / RIGHT) run
    in the profile rgb, where `RGBW.Set` does not exist. The profile is read from
    `Shelly.GetStatus` (`rgb:<id>` / `rgbw:<id>`) the first time and kept per address and
    channel; a command that fails forgets it. White for a device in the profile rgb is
    `UNSUPPORTED_CAPABILITY`. `transition_duration` is not sent - `shelly-client` 2.1.0 has no
    parameter for it.
  - Eaton: `/home/amx/eaton/lights/{point}?turn=` and `/level?value=`; a group point is a light of
    its own in the registry.
- **Commands** (`LightCommandService`): sent once, never retried, logged with their `source`.
  Anything a driver fails with becomes `DEVICE_FAILED`, a 502 whose answer carries the code alone
  — what the device said goes to the ERROR line of `LightExceptionProcessor`. A room goes one
  light after the other, each with its own error handling; a room where no light took the command
  fails. **A command writes no state** - whether a light is on comes from the devices (HAS-259).
- **`source` is the caller's word** — nothing behind the gateway is authenticated; the rules and
  the empty house will call the service in-process.
- **The codes of `LightError` are wire contract** (`LightErrorTest`); a new or renamed code
  needs a text in the dashboard.
- **The state (HAS-259) is merged in `LightStateTracker`**, in memory; `decide()` is pure and holds
  every rule, pinned by `LightStateTrackerTest`:
  - a row of `light_state` only when a light is switched or a known brightness / color changes
    (off too: the level it comes back at); a confirming reading touches the latest row (the
    highest id) and fills in what it adds - the presence-service shape, as blinds `blinds_state`;
  - what a report does not say is kept: an Eaton dimmer that reports `0` keeps its level, a Gen1
    action its brightness;
  - a change within `COMMAND_WINDOW` (2 min - two Gen1 polls lie more than 60 s apart) of
    `commanded()` takes the command's source; the command is used up by a change, never by a
    confirmation; otherwise the word of the Shelly device, or `external` (cut to 50);
  - the writes of one light go one after the other (a `Sinks.Empty` per write, only the last
    kept); the restore runs once (cached), before the first reading; times cut to microseconds.
- **Which channel says what** (`LightEventRouter`, `LightStatePoll`):
  - Gen2+: MQTT `status.<component>:<channel>` - the component of the kind (`switch` relay,
    `light` dimmer, `rgb` / `rgbw` an rgbw light, which also teaches `Gen2Profiles` the profile)
    and `online`, matched by `deviceId` case-insensitively. `GetStatus` every 5 min is a
    reconciliation that **never changes reachability** (MQTT owns it).
  - Gen1: `/status` every 60 s (`relays[]` for a relay, `lights[]` for the rest; the mode color by
    its gain), a failure makes the light unreachable; `GET /events/gen1?device=<registry id>&event=on|off`
    for the `out_on_url` / `out_off_url` actions - **not configured on any device yet** (no Shelly light
    is in the registry before HAS-269); they come through the gateway, which routes `/light` since 0.8.0. Unauthenticated
    like everything behind the gateway - anyone on the LAN can write a Gen1 state; the rules must not
    act on one the next poll has not confirmed.
  - Eaton: the `status` event on `eaton.<gateway>.<point>` - the gateway of the light's own binding,
    so a blind on the same point of the other gateway is never taken for it; a switch `0` / `1`, a
    dimmer its level. **`EatonStatusRequests` asks every Eaton light once** after the start (a minute
    in, a second apart, `POST /home/amx/eaton/lights/{point}/status` - amx-service ≥ 2.4.0); its
    answer is a status event like any other. A light that cannot be asked is a WARN, never repeated.
- **The reads leave out what is not known** (`LightView`, `NON_NULL`): nothing before the first
  report, no brightness of a relay, no `reachable` after a restart; a room's `anyOn` is left out
  while a light of it is unknown. A room read carries `occupancy` (HAS-260, below).
- **One instance, `Recreate`, never scaled**: the rules engine lives in its memory.
  `@EnableScheduling` is in `SchedulingConfig` under `@Profile("!test")`.
- **The test document points everything outward at port 1** (broker, database, the three
  services; `ApplicationYamlTest`): a context test must never switch a real light. A new
  outward address gets its line there.
- **What has no default in `home`** - the two broker passwords and the place of the house
  (`location-latitude` / `-longitude`, from the manifest, HAS-263) - is listed in
  `cholewa.required-properties`, which refuses the start before the first bean;
  `RequiredPropertiesTest` ties the list to the placeholders.
- **The notification connection is not a bean** (`NotificationRabbitConfig`): a
  `ConnectionFactory` or `RabbitOperations` bean would make `RabbitAutoConfiguration` back off,
  and the listener would lose manual ack, prefetch and observation. **The fourth copy** of the
  class (heating, blinds, weather, light) - kept by the owner's choice in HAS-257 instead of a
  move into `cholewa-commons`; a change goes into every copy.
- **Timeouts of the clients**: `internal.service.<name>.{connect,response}-timeout`, a bare
  number is seconds, bounded 1–60 s (`InternalServicePropertiesTest`); `amx` answers within
  10 s (it may read the registry and wait for the frame server first). Each client has its own
  named `ConnectionProvider` with 2 min idle / 30 min life (the gateway's HAS-212 limits).
- **A migration is rehearsed on a throwaway PostgreSQL, never by a local run** — `home,local`
  with the real `database-*` values is the production database. The recipe: `postgres:17` in
  Docker with `-c ssl=on` and the snakeoil certificate of the image (`MSYS_NO_PATHCONV=1` in
  Git Bash), then the jar with `home,local`, the five `--database-*` arguments and
  `--flyway-url=...?sslmode=require`.
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`. Tests use `mockwebserver3`, and a publisher is verified as
  `publisher.as(StepVerifier::create)` only.
- **The occupancy of a room** (HAS-260), package `occupancy`, all in memory - **a room is unknown
  after a start, never empty**, and no rule may act on an unknown room:
  - **The sensors are the registry's** (`OccupancyRegistry`, with the gateways, every five minutes in
    `OccupancyRefresh`; a failed read keeps the last lists): a `presence sensor` is a Shelly Gen2+ with
    its MQTT id and **a zone as its channel** (200-299, database-service >= 0.17.0; the three Presence
    Gen4 are 91-93 on zone 200); a `motion sensor` is a BLU Motion (`bthome`), a Shelly Motion Gen1 or a
    PIR of the AMX system. Anything else is skipped with a WARN.
  - **The rules are `OccupancyTracker`**, pinned by `OccupancyTrackerTest`: occupied when one presence
    sensor says so, empty when every one does, otherwise not known - a Presence that left the broker
    is not known; `lastMotionAt` the newest motion of any motion sensor; `illumination` (the word of a
    Presence: dark / twilight / bright at its 10 / 50 lx) and `lux` (a number, BLU Motion or Shelly
    Motion) each the newest of the room; `since` when the service saw the occupancy change.
  - **Which channel says what**: Presence - MQTT `status.presencezone:<zone>` (only its own zone: the
    office device has a second zone, `Door`, which nobody reads), `status.illuminance:0`, `online`;
    every five minutes `OccupancyReads` reads the zone (`Shelly.GetComponents` - it is a dynamic
    component) and `illuminance:0`, **never deciding whether the device is reachable** (MQTT owns
    it, as for the lights). BLU Motion - the `bthomesensor` components of object `0x21` (motion) and
    `0x05` (lux) of every registered gateway, mapped by `addr` every five minutes, then their status
    topics; the later `last_updated_ts` wins; reachable while a gateway confirmed it within
    `light.occupancy.gateway-silent-after` (`OccupancyProperties`, PT30M, 10 min - 24 h, minutes) -
    the rule of the window sensors of blinds-service. Shelly Motion Gen1 - `GET
    /events/gen1?device=<id>&event=motion|motion_end[&lux=]` from its `motion_on` / `motion_off`
    actions (no `end_of_motion` on fw v2.2.4); it sleeps, so no reachability. Eaton PIR - `switch on`
    / `switch off` on `eaton.<gateway>.<point>`; no reachability either.
  - `DeviceEventConsumer` hands every event to both routers, each with its own error handling.
  - **Every sensor of the house is in the registry** (2026-10-19): the three Presence Gen4 (91-93), the
    eight BLU Motion (94-100, 102 - adopted one by one, each seen on its gateway, the broker and here;
    `deployment-tools/scripts/shelly/devices.md` lists them; PRESENCE OFFICE became BTHome gateway
    101 for the one of bathroom up), the Shelly Motion of the sanctum (103, its actions call
    `/events/gen1` through the gateway since api-gateway-service 0.8.0, HAS-265) and the Eaton PIR
    of the stairs (51). The circulation sensor of bathroom up (100) reports motion rarely by its
    settings (it drives the hot-water circulation in Shelly Cloud) - do not read its silence as an
    empty room.
- **The rules of a room** (HAS-261), package `rule`, stored in `light_room_rule` (one row per room, the
  key is the `RoomName` constant) and `light_motion_period`:
  - **`RoomRuleRules` holds every rule**, pinned by `RoomRuleRulesTest`: `check()` the body alone,
    `checkRoom()` against the lights (`LightRegistry`) and sensors (`OccupancyRegistry`, which must have
    been read once - otherwise `REGISTRY_UNAVAILABLE`). Nothing has a default; the delay of an enabled
    rule is required, every delay 1-180 min; periods are half-open, a `to` before `from` crosses
    midnight, the same time is the whole day; a period's brightness / colour are optional (the light
    keeps its own) but refused in a room that cannot take them. **A room whose only sensors are Eaton
    PIRs gets no rule** (the halls stay in NetLinx, owner via HAS-262); motion-on needs a motion sensor,
    a presence sensor alone is not one. `INVALID_RULE` answers the first broken rule in words.
  - **A rule is judged at write time only**: a light or sensor removed from the registry later leaves
    a stored rule that no longer fits - the engine treats it as nothing to do, never a failed pass.
  - **The resident's switch survives the administrator**: `PUT /rules` is an upsert
    (`RoomRuleRepository.upsert`, `ON CONFLICT`) that never sets `enabled_by_resident` - a new rule
    starts `true`. Never `save()` a `RoomRuleEntity`: with its key set it is taken for an update.
  - **Only the writes are the transaction** (`write()`): the registry may be read over HTTP before,
    and a connection held meanwhile would be half of the pool.
  - `"r,g,b"` of a colour is `RgbColumn`, shared with the state.
- **The rules engine** (HAS-262), `RuleEngine` around the pure `RuleEvaluator` (pinned by `RuleEvaluatorTest`,
  no I/O) - it never fights a person and never acts on a room it cannot judge:
  - **Two triggers**: a fresh motion (`OccupancyTracker.motion` / `bluMotion` answer whether a motion was
    taken; `OccupancyEventRouter` and the Gen1 action hand it to `RuleEngine.motion(room)`, which **completes at
    once and reacts on its own** with the caller's context (`deferContextual` + `contextWrite`, so the trace goes
    on): neither the device queue nor a Shelly Motion's HTTP call waits for the commands or can cancel them; the
    room is judged `SETTLE` (1 s) after the motion, so the lux of a BLU Motion's own packet - a second component,
    published after the motion - counts) and the pass of every minute (`@Scheduled` fixedDelay PT1M, initial PT1M, bounded by
    50 s, everything inside `Mono.defer`), which reads the rules again into `RuleBook`. **`RuleBook` is the
    engine's copy of the rules**: every write of `RoomRuleService` puts its answer there at once, a pass that
    read before a write may bring the old rule back for a minute.
  - **The manual hold is derived from the state, not stored**: a light whose last change has a source other
    than `rule` / `empty-house` (a wall switch, the Shelly app, an AMX panel, the dashboard, `external`) is held
    for the room's `manualHoldMinutes` from its `changedAt` - so it survives a restart with `light_state`.
    The reads answer it as `heldUntil` while it lasts (by the `RuleBook`; left out with no rule).
  - **Once**: a light the engine sent a command to gets no other until it reports a change (`changedAt` after the
    command) - a failed or unconfirmed command is not repeated every minute; the memory of it is lost with a
    restart. A pass cut off by its 50 s leaves the lights it never got to unmarked (the one in flight stays). The decision and the note of its commands are taken together (one lock), so a motion and the pass
    never both command a light.
  - **Auto-off**: lights known on and not held, `autoOffDelayMinutes` after the room is empty - with a presence
    sensor from its `since` (or a later motion), with motion sensors only from the last motion. A presence
    sensor not known, a motion sensor not reachable, or no motion since the start: the room is skipped and named
    once an hour (INFO).
  - **Motion-on**: a motion at most 2 min old (`FRESH_MOTION` - a gateway republishes its last motion when it
    reconnects; a BLU motion is stamped by the gateway's clock, so a gateway more than 2 min behind lights nothing), lux below `onlyBelowLux` (**a lux not known never holds**), the period of the moment (none:
    nothing); every light not on and not held gets a colour (rgbw and a coloured period), a brightness (one that
    dims) or on. **Motion-off**: `motionOffAfterMinutes` after the last motion, only the lights whose source is
    `rule`, unless a presence sensor of the room says somebody is there or is not known.
  - **`light_rule_run`** (V3) logs every command with its outcome (`error` = the `LightError` code); a log that
    cannot be written is a WARN. `GET /rooms/{room}/rules/runs?from=&to=` (ISO date-times, from included, to
    not, at most 7 days, any room - the runs of a removed rule stay). No retention yet.
  - **The once-guard and the 2-minute command window of `LightStateTracker` meet on a Gen1 light without
    actions**: a change seen more than 2 min after the rule's command is `external` and starts a hold.
- **The empty house** (HAS-263), package `emptyhouse`:
  - **`HouseState`** - whether anybody is at home, from `GET /home/presence/residents/presence` every minute
    (`EmptyHouseEngine.tick`, initial PT1M): empty when every active member is away by a reading newer than
    5 min; a member never observed, no members, a stale reading or a failed call is **unknown, never empty**.
    Only somebody at home ends an empty house - an unknown spell suspends acting but keeps `emptySince`, so it
    neither restarts the delay nor announces twice. Held apart from the engine so that `RuleEngine` reads it:
    **the rules of the rooms pause while the house is empty** (`active` - after `light.empty-house.after`,
    PT10M, 1 min - 2 h, minutes), or they would switch the simulation off.
  - **`EmptyHouseEvaluator`** (pure): what should be lit - nothing by day, the simulation of the minute by night -
    against the state; a light known on that should not be goes off, one that should be lit and is not known on
    goes on. **No manual hold** (nobody is at home), the once-guard of the rules. Source `empty-house` (starts no
    hold), reason `empty-house` by day or `presence-simulation` in `light_rule_run`. One info notification per
    empty house for the lights switched off by day (their rooms), one when the simulation starts.
    `light.empty-house.enabled: false` keeps it from acting; it is still watched and answered.
  - **`SimulationPlanner`** (pure, `SimulationPlannerTest` over 60 nights): the night of a date is its civil dusk
    to the next sunrise (`SunCalendar` - **a copy of the one of blinds-service**, with the civil dusk; the second
    copy); a phase runs only inside it, its `from` / `to` (after noon the evening, before noon the morning after)
    each moved by up to `jitterMinutes`. **Seeded by the date**: the same night always comes out the same (a
    restart picks up), another night differs. Continuous phases keep somebody in a `stay` spot (sometimes two,
    `maxSpotsAtOnce`), a `path` lit for the two minutes around every other move, a `closing` spot 5-15 min after
    the end; a visits phase is `visitsMin`-`visitsMax` short stays with their path. The engine asks it every
    minute what is lit now - a desired state, not a list of commands.
  - **The plan** (`SimulationPlanService`, `PlanRules`): `GET` / `PUT /empty-house/plan`, whole, every field
    required, `INVALID_PLAN` with the reason (a new code of `LightError` - the dashboard needs a text); every light
    of a spot must be a registry light of the room of the spot. A phase row has its name as the key, so it is
    inserted by `R2dbcEntityTemplate.insert`, never `save()`. Read by the engine only by night while the house is
    empty. No plan: an empty house only gets its lights switched off. `GET /empty-house` answers the house,
    `emptySince`, `actingFrom`, `active`, the night, and by night a preview of what is lit and the next change.
  - **The lights of the house were completed for it** (owner, 2026-10-19; registry 105-128,
    `deployment-tools/scripts/shelly/devices.md`): every Shelly light, sanctum and sauna included; 105, 106 and
    116 checked by switching, the rest to check by day.
