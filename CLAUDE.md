# light-service

The lights of the house (epic HAS-256): switching the Eaton and Shelly lights, knowing whether
they are really on and switching them by the rules of each room - motion, presence and an empty
house. Since HAS-257 it consumes the device events; since HAS-258 it knows the lights from the
device registry and switches them on command through three drivers (`POST /home/light/...`).

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The owner delegated the lighting epic:
Claude implements its tasks here ("zajmij się HAS-n"), as in blinds-service and weather-service.

## Role in the system

- Talks to: RabbitMQ — consumes `device.prod.light` on `/device` (user `device`), publishes
  notifications to `/notification` (user `notification`, a second connection); calls
  `database-service` (device registry), `amx-service` (the Eaton lights) and `presence-service`
  (who is at home) over k8s DNS (`internal.service.*`). The command endpoints (README) are not
  routed by `api-gateway-service` yet (HAS-265); the dashboard will branch on their codes.
- Uses libraries: `cholewa-commons` 1.9.0 (pool, error contract, `DownstreamErrors`, connection
  names, `NotificationPublisher`, `cholewa.required-properties`), `smart-home-sdk` 1.11.0
  (`SmartDevice` and `LightKind` of the registry), `shelly-client` 2.1.0 (`ShellyGen1Client`,
  `ShellyRpcClient`; `ShellyHttpClientCustomizer` puts their calls into the log).
- Database: own database `home-automation-light`, pool `database.pool.max-size: 2` (heating 2 /
  database 4 / water 2 / presence 2 / blinds 2 / weather 2 / light 2 = 16 of 22), pinned by
  `LightServiceApplicationTest`. No schema yet - **the first migration is `V1`**.
- Integrates with hardware: the Eaton lights through `amx-service`, the Shelly lights over HTTP
  (commands) and MQTT (status topics) - from HAS-258 on.

## Build & run

- Build + tests: `mvn verify`; no test needs Docker yet. The Testcontainers dependencies are
  there for the first repository test of PostgreSQL-only SQL - from then on it needs a running
  Docker and fails without one.
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
- **One instance, `Recreate`, never scaled**: the rules engine will live in its memory.
  `@EnableScheduling` is in `SchedulingConfig` under `@Profile("!test")`.
- **The test document points everything outward at port 1** (broker, database, the three
  services; `ApplicationYamlTest`): a context test must never switch a real light. A new
  outward address gets its line there.
- **What has no default in `home`** - the two broker passwords - is listed in
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
