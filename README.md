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

Since HAS-258 it knows the lights from the device registry and switches them on command; since
HAS-259 it knows whether each light is really on, whoever switched it; since HAS-260 it knows from
the presence and motion sensors whether a room is occupied, when it last saw motion and how light it
is; since HAS-261 it stores the rules of each room, which the administrator writes and a resident
switches off; since HAS-262 the engine runs them - auto-off, motion-on and motion-off, never against a
person and never in a room it cannot judge - and logs every command it sends.

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

- **The real state** of every light - a wall switch, the Shelly app, an AMX panel or this
  service - comes only from what the devices report, never from a command:

  | | how | reachability |
  |---|---|---|
  | Shelly Gen2+ | its MQTT status (`switch`, `light`, `rgb` / `rgbw`) at once; `GetStatus` every 5 minutes reconciles | its `online` topic |
  | Shelly Gen1 | `/status` every 60 s; `GET /events/gen1` when the device calls it (its `out_on_url` / `out_off_url`) | a poll that fails |
  | Eaton | the `status` event of `amx-service` (a switch on or off, a dimmer its level); every Eaton light is asked once after the start | - |

  It is kept in memory and stored in `light_state` - a row when a light is switched or its
  brightness or color changes, a confirmation moves `last_checked_at` - and read back at the
  start. A change within 2 minutes of a command of this service is attributed to the command's
  `source`, otherwise to what the Shelly device names (`button`, `switch`) or `external`.

- **The occupancy of a room** comes from the switched-on presence and motion sensors of the registry
  and the BTHome gateways (read every 5 minutes), held in memory only - **after a start every room is
  unknown, never empty**:

  | | how | reachability |
  |---|---|---|
  | Shelly Presence Gen4 (`presence sensor`, a zone as its channel - 200 is the main zone) | MQTT `presencezone:<zone>` and `illuminance:0` at once; the zone and the light read every 5 minutes reconcile | its `online` topic |
  | Shelly BLU Motion (`motion sensor`, `bthome`) | the `bthomesensor` components of motion (`0x21`) and illuminance (`0x05`) of every BTHome gateway, mapped every 5 minutes, then their MQTT status | a gateway confirmed it within 30 minutes |
  | Shelly Motion Gen1 (`motion sensor`, generation 1) | `GET /events/gen1` with `event=motion\|motion_end` from its `motion_on` / `motion_off` actions | - (it sleeps) |
  | Eaton PIR (`motion sensor`, `eaton`) | `switch on` / `switch off` of `amx-service` | - |

  A room is occupied when one presence sensor says so, empty when every one does, and not known
  otherwise - also while one of them is off the broker. `lastMotionAt` is the newest motion of any
  motion sensor of the room, `illumination` the newest word of a Presence (`dark`, `twilight`,
  `bright`), `lux` the newest number of a motion sensor.
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
- **The rules of a room** (HAS-261) are data of the service's own database, written whole by the
  administrator and switched off by a resident (below); the engine (HAS-262) runs them at every motion
  and every minute (below).
- **The rules run in the memory of one instance**: the deployment is `Recreate` and never
  scaled; scheduling is off under the `test` profile.

# Run locally

- Build: `mvn verify` (JDK 21) **needs a running Docker**: `LightStateRepositoryTest` runs the
  PostgreSQL-only SQL of the state and the rules on a Testcontainers PostgreSQL
  (`LightStateRepositoryTest`, `RoomRuleServiceTest`). The decisions of the engine are tested without
  I/O (`RuleEvaluatorTest`).
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

Base path `/home/light` (`spring.webflux.base-path`), routed by `api-gateway-service` >= 0.8.0
(`/home/light`, `/home/light/**`, every method - HAS-265). Every command answers **202** once the device (or `amx-service`) took it - never once
the light is on. `source` is `dashboard` (the default), `rule` or `empty-house`; every value is
taken exactly as written.

| Method | Path | Parameters / body | Answer |
|---|---|---|---|
| `POST` | `/{id}` | `turn=on\|off`, `source` | 202 |
| `POST` | `/{id}/brightness` | `value=1..100` (switches the light on), `source` | 202 |
| `POST` | `/{id}/color` | `{"rgb": [r, g, b], "white": w, "brightness": b}` - rgb and white 0..255, brightness 1..100, each part optional (the device keeps what is left out), at least one; switches the light on. `source` | 202 |
| `POST` | `/rooms/{room}` | `turn=on\|off`, `source`; the room as the registry writes it (`living room`) | 202 with `{room, turn, switched: [{id, name}], failed: [{id, name, code}]}` |
| `GET` | the base path itself | - | 200, every light by room: `[{room, anyOn, lights: [...], occupancy: {...}}]` |
| `GET` | `/rooms/{room}` | - | 200, `{room, anyOn, lights: [...], occupancy: {...}}` |
| `GET` | `/rooms/{room}/occupancy` | - | 200, the occupancy of the room (below) - also of a room without lights or sensors |
| `GET` | `/{id}` | - | 200, one light |
| `GET` | `/rules` | - | 200, every room with a rule: `[{room, ...}]` |
| `GET` | `/rooms/{room}/rules` | - | 200, the rule of the room (below); 404 `NOT_FOUND_RULE` without one |
| `PUT` | `/rooms/{room}/rules` | the rule, whole (below) | 200, the rule as stored - created or replaced; the switch of the resident is kept |
| `DELETE` | `/rooms/{room}/rules` | - | 204 |
| `PUT` | `/rooms/{room}/rules/resident-enabled` | `{"enabled": false}` - the switch of My room | 200, the rule as stored |
| `GET` | `/rooms/{room}/rules/runs` | `from`, `to` - ISO date-times (`2026-10-19T00:00:00`), from included, to not, at most 7 days apart | 200, what the engine sent in the room: `[{deviceId, action, reason, error, at}]` (below) |
| `GET` | `/events/gen1` | `device=<registry id>`, `event=on\|off` - called by a Gen1 light (`out_on_url` / `out_off_url`); or `event=motion\|motion_end` and an optional `lux=0..200000` - called by a Shelly Motion (`motion_on` / `motion_off`) | 204; a device that is not a Gen1 light / Gen1 motion sensor of the registry 404 |

A light in a read: `id`, `name`, `room`, `vendor`, `kind` from the registry, and `on`,
`brightness` (a dimmer or an rgbw light; off too - the level it comes back at), `color`
(`{rgb, white}`, an rgbw light), `changedAt`, `source`, `reachable` from the devices, and `heldUntil` -
the rules of the room leave the light alone until then, after a change that was not theirs. **A field the
service does not know is left out**, never answered as off or zero. `anyOn` of a room is `true`
when a light is on, `false` when every light is known to be off, and left out otherwise.

The occupancy of a room: `room`, `occupied` (`true` / `false`), `since` (when the service saw it
change), `lastMotionAt`, `illumination` (`dark`, `twilight`, `bright`), `lux`, and `sensors: [{id, name,
kind, lastReportAt, reachable}]` - **each left out while it is not known**: no rule may act on a room
that only looks empty.

A room is switched one light after the other; one that fails is named in `failed`, the rest still
switch. It fails as a whole only when no light took the command.

| Status | `code` | When |
|---|---|---|
| 400 | - | a parameter or a body that is not a command |
| 400 | `UNSUPPORTED_CAPABILITY` | a brightness for a relay, a color for a light without one, white for a Gen2 device in the profile rgb |
| 404 | `NOT_FOUND_LIGHT` | no switched-on light with that id, no light in the room |
| 502 | `DEVICE_FAILED` | the device or `amx-service` did not take the command - it may have been carried out; the answer carries the code alone, the device message is in the log |
| 400 | `INVALID_RULE` | a rule the service will not store - the reason in `details` (below) |
| 404 | `NOT_FOUND_RULE` | no rule for the room |
| 502 | `REGISTRY_UNAVAILABLE` | the device registry could not be read since the start (a light, a Gen1 motion sensor, or the lights and sensors a rule is judged by) |

# The rules of a room

A room has no rule until the administrator writes one; `PUT` sends it whole - **nothing has a
default**, a field left out is refused, never read as off:

| Field | |
|---|---|
| `autoOffEnabled`, `autoOffDelayMinutes` | every light of the room off that many minutes after the room is empty - with a presence sensor after it says so, with motion sensors only after the last motion |
| `motionOnEnabled`, `onlyBelowLux`, `motionOffAfterMinutes` | the lights on at a motion while the room is darker than `onlyBelowLux` (optional - left out, whatever the light), off again that many minutes after the last motion |
| `periods: [{from, to, brightness, rgb, white}]` | the scheme of the motion per time of day: `HH:mm`, a `to` before `from` is the next day, the same time the whole day; `brightness` 1..100, `rgb` 3 × 0..255, `white` 0..255 - each optional, a light keeps what is left out. Outside every period a motion switches nothing on |
| `manualHoldMinutes` | a light somebody else switched or changed is left alone that long |

The answer adds `room`, `enabledByResident` (false stops both rules of the room - the switch of
My room, which a write of the administrator keeps) and `updatedAt`, and leaves out a delay or a
lux that was not given.

`INVALID_RULE` names the first rule broken: a switch or `manualHoldMinutes` missing, the delay of
an enabled rule missing, a delay outside 1..180 minutes, `onlyBelowLux` outside 1..100000, `periods`
missing, more than 12 or overlapping, motion-on without a period, a value of a period out of range;
and against the registry - a room without lights, **a room whose only sensors are Eaton PIRs** (the
halls stay in NetLinx), auto-off without a presence or motion sensor, motion-on without a motion
sensor, a brightness without a light that dims, a colour without an rgbw light.

A bedroom that switches itself off 15 minutes after it is empty, a light switched by hand left for an
hour:

```json
{
  "autoOffEnabled": true, "autoOffDelayMinutes": 15,
  "motionOnEnabled": false,
  "periods": [],
  "manualHoldMinutes": 60
}
```

A hall lit by motion below 30 lx - bright in the evening, a dim warm light at night, nothing by day -
off 3 minutes after the last motion (the colour needs an rgbw light in the hall):

```json
{
  "autoOffEnabled": true, "autoOffDelayMinutes": 10,
  "motionOnEnabled": true, "onlyBelowLux": 30, "motionOffAfterMinutes": 3,
  "periods": [
    { "from": "16:00", "to": "22:00", "brightness": 80 },
    { "from": "22:00", "to": "06:00", "brightness": 10, "rgb": [255, 120, 0] }
  ],
  "manualHoldMinutes": 30
}
```

# The engine

The rules run at every fresh motion (the reaction runs apart from the event, a second after it, so the
lux of the same sensor packet counts) and in a pass every minute, which also reads the rules again; every
write of a rule takes effect at once. Commands go through the same drivers with `source=rule`.

- **Auto-off**: every light known to be on and not held goes off `autoOffDelayMinutes` after the room is
  empty - with a presence sensor from when it said nobody is there (or a later motion), with motion
  sensors only from the last motion. A room whose presence sensor is not known, whose motion sensor is not
  reachable, or which saw no motion since the start is skipped and logged once an hour.
- **Motion-on**: at a motion at most 2 minutes old, while the room is darker than `onlyBelowLux` (a lux
  not known never holds), every light that is not on and not held gets what the period of the moment
  gives - a colour for an rgbw light, a brightness for one that dims, otherwise on. Outside every period:
  nothing.
- **Motion-off**: `motionOffAfterMinutes` after the last motion the lights the rule switched on go off,
  unless a presence sensor of the room says somebody is there or is not known.
- **Manual hold**: a light whose last change was not this service's (a wall switch, the Shelly app, an AMX
  panel, the dashboard) is left alone for `manualHoldMinutes` after that change - `heldUntil` in the reads.
- **Once**: a light gets no second command until it reports a change; a command that failed is not
  repeated every minute.
- A rule switched off by the resident does nothing.

A run: `deviceId` (the light), `action` (`on` / `off`), `reason` (`auto-off`, `motion-on`, `motion-off`),
`error` (the code when the light did not take the command - left out when it did), `at`.

# Database

- **Own database** `home-automation-light` on the managed PostgreSQL, reactive access via
  `r2dbc-postgresql`, migrations by Flyway from `src/main/resources/db/migration`.
- `light_state` (`V1`, HAS-259): one row per period of an unchanged state of a light - `device_id`
  (the registry id), `switched_on`, `brightness`, `rgb` (`r,g,b`), `white`, `source`,
  `started_at`, `last_checked_at`; the latest row of a light is its highest id.
- `light_room_rule` and `light_motion_period` (`V2`, HAS-261): one row per room with a rule (the
  room by the name of its `RoomName` constant - renaming one needs a migration of the rows) and its
  periods, gone with it.
- `light_rule_run` (`V3`, HAS-262): every command of the engine - `room`, `device_id`, `action`,
  `reason`, `error`, `at`; the runs of a removed rule stay.
