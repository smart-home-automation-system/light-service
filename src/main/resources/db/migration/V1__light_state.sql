-- One row per period of an unchanged state of a light: a row is inserted only when the light is
-- switched or its brightness or color changes, a reading that confirms it moves last_checked_at (and
-- fills in what the row did not know) of the latest row of the light. device_id is the id of the
-- light in the device registry of database-service - another database, so there is no foreign key;
-- the rows of a removed light stay.
-- brightness, rgb and white are NULL where the light did not say (a relay never does); rgb is
-- "r,g,b". source is who changed it: dashboard, rule, empty-house (a command of this service), the
-- word of a Shelly device, or external
CREATE TABLE light_state
(
    id              BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    device_id       BIGINT             NOT NULL,
    switched_on     BOOLEAN            NOT NULL,
    brightness      SMALLINT,
    rgb             VARCHAR(11),
    white           SMALLINT,
    source          VARCHAR(50)        NOT NULL,
    started_at      TIMESTAMP          NOT NULL,
    last_checked_at TIMESTAMP          NOT NULL
);

ALTER TABLE light_state
    ADD CONSTRAINT light_state_brightness_check CHECK (brightness BETWEEN 0 AND 100);

ALTER TABLE light_state
    ADD CONSTRAINT light_state_white_check CHECK (white BETWEEN 0 AND 255);

ALTER TABLE light_state
    ADD CONSTRAINT light_state_rgb_check CHECK (rgb ~ '^[0-9]{1,3},[0-9]{1,3},[0-9]{1,3}$');

-- the latest row of a light is its highest id: read at the start, updated by every confirmation
CREATE INDEX light_state_device_latest_idx ON light_state (device_id, id DESC);
