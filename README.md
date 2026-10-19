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

This release is the **skeleton** (HAS-257): the service starts, takes the events of every
device off its queue and has its clients ready, but it does nothing with them yet and has no
endpoints.

Everything is non-blocking (Spring WebFlux / Reactor):

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

Base path `/home/light` (`spring.webflux.base-path`). No endpoints yet; the service is not
routed by `api-gateway-service` (HAS-265).

# Database

- **Own database** `home-automation-light` on the managed PostgreSQL, reactive access via
  `r2dbc-postgresql`, migrations by Flyway from `src/main/resources/db/migration`.
- **No schema yet**: the first migration comes with the first table.
