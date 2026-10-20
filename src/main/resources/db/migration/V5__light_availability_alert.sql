-- The open alerts of the availability monitor (HAS-264), one row per device the household was told about and
-- not yet told is back - kept here, not in memory, so that a restart neither repeats an alert nor forgets to
-- announce a return (the shape of temperature_sensor_alert of heating-service). device_id is the registry id;
-- kind light (a Shelly light that does not answer), presence sensor or battery sensor (silent); since when it
-- is out, last_alert_at the alert or the last reminder
CREATE TABLE light_availability_alert
(
    device_id     BIGINT PRIMARY KEY NOT NULL,
    kind          VARCHAR(20)        NOT NULL,
    name          VARCHAR(200)       NOT NULL,
    room          VARCHAR(50)        NOT NULL,
    since         TIMESTAMP          NOT NULL,
    last_alert_at TIMESTAMP          NOT NULL
);

ALTER TABLE light_availability_alert
    ADD CONSTRAINT light_availability_alert_kind_check CHECK (kind IN ('light', 'presence sensor', 'battery sensor'));
