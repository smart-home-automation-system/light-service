# light-service

The lights of the house (epic HAS-256): switching the Eaton and Shelly lights, knowing whether
they are really on and switching them by the rules of each room - motion, presence and an empty
house. Since HAS-257 it is a skeleton: it consumes the device events and has its clients ready,
but acts on nothing and has no endpoints.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The owner delegated the lighting epic:
Claude implements its tasks here ("zajmij się HAS-n"), as in blinds-service and weather-service.

## Role in the system

- Talks to: RabbitMQ — consumes `device.prod.light` on `/device` (user `device`), publishes
  notifications to `/notification` (user `notification`, a second connection); calls
  `database-service` (device registry), `amx-service` (the Eaton lights) and `presence-service`
  (who is at home) over k8s DNS (`internal.service.*`). Not routed by `api-gateway-service`
  yet (HAS-265).
- Uses libraries: `cholewa-commons` 1.9.0 (pool, error contract, connection names,
  `NotificationPublisher`, `cholewa.required-properties`), `smart-home-sdk` 1.11.0 (nothing
  read yet - the registry models come with HAS-258), `shelly-client` 2.1.0 (its three beans;
  `ShellyHttpClientCustomizer` puts their calls into the log).
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
