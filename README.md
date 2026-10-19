# light-service

[![CI](https://github.com/smart-home-automation-system/light-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/light-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_light-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_light-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_light-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_light-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/light-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/light-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/light-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.1-blue?style=plastic)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_light-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_light-service)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_light-service&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_light-service)

![GitHub issues](https://img.shields.io/github/issues/smart-home-automation-system/light-service?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/smart-home-automation-system/light-service?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/smart-home-automation-system/light-service?style=plastic)

![GitHub last commit](https://img.shields.io/github/last-commit/smart-home-automation-system/light-service?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/smart-home-automation-system/light-service?style=plastic)

---


# Description

The lights of the house: switching them, knowing whether they are really on and switching them
by the rules of each room - motion, presence and an empty house (epic HAS-256). The lights are
Eaton devices, switched through `amx-service`, and Shelly devices.

Since HAS-258 it knows the lights from the device registry and switches them on command; the
real state of a light (HAS-259) and the rules of the rooms come next.

Everything is non-blocking (Spring WebFlux / Reactor):

- **The lights** are the switched-on lights of the device registry of `database-service`
  (`kind=light`), read at the start and every 5 minutes. A failed read keeps the last list; until
  one read has succeeded a command reads the registry itself. A light without `lightKind`, or one
  the service cannot reach (no address, generation or point), is skipped with a WARN.
- **What a light can do** follows its `lightKind`: a `relay` switches, a `dimmer` also takes a
  brightness, an `rgbw` light also takes a color. The commands go to the device once and are never
  repeated:

  | | relay | dimmer | rgbw |
  |---|---|---|---|
  | Shelly Gen1 | `/relay/<channel>` | `/light/<channel>` | `/color/<channel>`, the brightness as the gain |
  | Shelly Gen2+ | `Switch.Set` | `Light.Set` | `RGB.Set` or `RGBW.Set` - the profile of the device, read once from `Shelly.GetStatus` |
  | Eaton | `amx-service` `/home/amx/eaton/lights/{point}?turn=` | and `/level?value=` | - |

- **Device events** arrive from RabbitMQ, virtual host `/device` (user `device`), queue
  `device.prod.light` (`device.dev.light` in the `local` profile). The queue is bound to
  `amq.topic` with `#`, so it gets every event of every device — the status topics of the
  Shelly devices over MQTT and the `DeviceEvent` of the Eaton devices published by
  `amx-service` — about 130 a minute. The listener acknowledges by hand with a prefetch of 3;
  each event is logged at `DEBUG` and acknowledged.
- **Notifications** go to the `notification` exchange of the virtual host `/notification`
  over a second connection (`NotificationPublisher` of `cholewa-commons`), named
  `<pod>/notification`.
- **Clients** of `database-service` (the device registry), `amx-service` (the Eaton lights)
  and `presence-service` (who is at home), each a `WebClient` over k8s DNS with its own
  connect and response timeouts and its own connection pool (`internal.service.*`).
- **The rules run in the memory of one instance**: the deployment is `Recreate` and never
  scaled; scheduling is off under the `test` profile.

# Run locally

- Build: `mvn verify` (JDK 21). No test needs Docker yet; the Testcontainers dependencies are
  there for the first repository test of PostgreSQL-only SQL, and from then on `mvn verify`
  needs a running Docker.
- Ports: local profile `6012` (management `8012`); in the deployed `home` profile the service
  listens on `6200` and Actuator on `8200` like every service in the cluster. The probes hit
  `/actuator/health/{readiness,liveness}` and Prometheus scrapes `/actuator/prometheus`.
- Profiles `home,local`. The broker is `localhost:5672`; without `rabbitmq-password` and
  `notification-rabbitmq-password` a local run starts with a dummy password and neither
  consumes nor publishes. In the `home` profile both variables are mandatory — without one
  the service does not start.
- The other services are called on their local ports: `database-service` 6005,
  `amx-service` 6001, `presence-service` 6009.
- The database connection properties (`database-host`, `database-port`, `database-name`,
  `database-user`, `database-password`) are bound to the `database.*` prefix that
  `cholewa-commons` consumes; Flyway derives its JDBC URL from them (`--flyway-url=...`
  overrides it). Only `database.pool.max-size: 2` is pinned here, this service's share of the
  22 backend connections of the managed database. **A run with the real `database-*` values
  talks to the production database** — rehearse on a throwaway PostgreSQL instead.

# API

Base path `/home/light` (`spring.webflux.base-path`). Not routed by `api-gateway-service` yet
(HAS-265). Every command answers **202** once the device (or `amx-service`) took it - never once
the light is on. `source` is `dashboard` (the default), `rule` or `empty-house`; every value is
taken exactly as written.

| Method | Path | Parameters / body | Answer |
|---|---|---|---|
| `POST` | `/{id}` | `turn=on\|off`, `source` | 202 |
| `POST` | `/{id}/brightness` | `value=1..100` (switches the light on), `source` | 202 |
| `POST` | `/{id}/color` | `{"rgb": [r, g, b], "white": w, "brightness": b}` - rgb and white 0..255, brightness 1..100, each part optional (the device keeps what is left out), at least one; switches the light on. `source` | 202 |
| `POST` | `/rooms/{room}` | `turn=on\|off`, `source`; the room as the registry writes it (`living room`) | 202 with `{room, turn, switched: [{id, name}], failed: [{id, name, code}]}` |

A room is switched one light after the other; one that fails is named in `failed`, the rest still
switch. It fails as a whole only when no light took the command.

| Status | `code` | When |
|---|---|---|
| 400 | - | a parameter or a body that is not a command |
| 400 | `UNSUPPORTED_CAPABILITY` | a brightness for a relay, a color for a light without one, white for a Gen2 device in the profile rgb |
| 404 | `NOT_FOUND_LIGHT` | no switched-on light with that id, no light in the room |
| 502 | `DEVICE_FAILED` | the device or `amx-service` did not take the command - it may have been carried out; the answer carries the code alone, the device message is in the log |
| 502 | `REGISTRY_UNAVAILABLE` | the device registry could not be read since the start |

# Database

- **Own database** `home-automation-light` on the managed PostgreSQL, reactive access via
  `r2dbc-postgresql`, migrations by Flyway from `src/main/resources/db/migration`.
- **No schema yet**: the first migration comes with the first table.
