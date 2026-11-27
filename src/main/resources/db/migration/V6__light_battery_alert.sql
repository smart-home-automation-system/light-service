-- The BLU Motions whose battery is low (HAS-530), one row per sensor the technical channel was warned about and not
-- yet told is back above the threshold - kept here, not in memory, so that a restart neither warns again nor forgets
-- to tell a replaced battery. A table of its own, not a kind of light_availability_alert: its key is the device, and a
-- sensor may be silent and low at once. device_id is the registry id; level the per cent when it was warned about;
-- since when it was first seen below the threshold
CREATE TABLE light_battery_alert
(
    device_id BIGINT PRIMARY KEY NOT NULL,
    name      VARCHAR(200)       NOT NULL,
    room      VARCHAR(50)        NOT NULL,
    level     SMALLINT           NOT NULL,
    since     TIMESTAMP          NOT NULL
);

ALTER TABLE light_battery_alert
    ADD CONSTRAINT light_battery_alert_level_check CHECK (level BETWEEN 0 AND 100);
