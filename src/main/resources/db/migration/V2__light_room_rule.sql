-- The rules of a room (HAS-261): data the administrator edits on the dashboard and a resident can
-- switch off in their room, so they live here and not in the configuration of the service. A room
-- has no rule until the administrator creates one; one row per room at most.
--
-- room is the name of a RoomName constant of the SDK (LIVING_ROOM, HALL_UP); renaming a constant
-- needs a migration of these rows. The CHECKs repeat what one row can say of a rule - RoomRuleRules
-- says the rest, in words where these would answer with the name of a constraint.
--
-- auto_off: every light of the room off auto_off_delay_min after the room is empty. motion_on: the
-- lights on at a motion while the room is darker than only_below_lux (optional), off again
-- motion_off_after_min after the last motion. manual_hold_min: a light somebody else changed is left
-- alone that long. enabled_by_resident false stops both rules of the room - the switch of My room,
-- which a write of the administrator keeps. A delay of a rule that is off may be kept for later
CREATE TABLE light_room_rule
(
    room                 VARCHAR(50) PRIMARY KEY NOT NULL,
    auto_off_enabled     BOOLEAN                 NOT NULL,
    auto_off_delay_min   SMALLINT,
    motion_on_enabled    BOOLEAN                 NOT NULL,
    only_below_lux       INTEGER,
    motion_off_after_min SMALLINT,
    manual_hold_min      SMALLINT                NOT NULL,
    enabled_by_resident  BOOLEAN                 NOT NULL,
    updated_at           TIMESTAMP               NOT NULL
);

ALTER TABLE light_room_rule
    ADD CONSTRAINT light_room_rule_delays_check CHECK (
        auto_off_delay_min BETWEEN 1 AND 180
            AND motion_off_after_min BETWEEN 1 AND 180
            AND manual_hold_min BETWEEN 1 AND 180);

-- a rule that is on has its delay. The IS NOT NULLs are not redundant: a CHECK that comes out NULL
-- lets the row in
ALTER TABLE light_room_rule
    ADD CONSTRAINT light_room_rule_auto_off_check CHECK (NOT auto_off_enabled OR auto_off_delay_min IS NOT NULL);

ALTER TABLE light_room_rule
    ADD CONSTRAINT light_room_rule_motion_on_check CHECK (NOT motion_on_enabled OR motion_off_after_min IS NOT NULL);

ALTER TABLE light_room_rule
    ADD CONSTRAINT light_room_rule_lux_check CHECK (only_below_lux BETWEEN 1 AND 100000);

-- The scheme of motion_on per time of day: a motion between from_time and to_time switches the lights
-- on with brightness, rgb ("r,g,b") and white - each NULL where the lights keep what they have. A
-- to_time before from_time is the next day (22:00 to 06:00), the same time the whole day. Periods do
-- not overlap and may leave gaps - no light outside them; RoomRuleRules checks it, one row cannot.
-- Gone with the rule of its room
CREATE TABLE light_motion_period
(
    id         BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    room       VARCHAR(50)        NOT NULL REFERENCES light_room_rule (room) ON DELETE CASCADE,
    from_time  TIME               NOT NULL,
    to_time    TIME               NOT NULL,
    brightness SMALLINT,
    rgb        VARCHAR(11),
    white      SMALLINT
);

ALTER TABLE light_motion_period
    ADD CONSTRAINT light_motion_period_brightness_check CHECK (brightness BETWEEN 1 AND 100);

ALTER TABLE light_motion_period
    ADD CONSTRAINT light_motion_period_white_check CHECK (white BETWEEN 0 AND 255);

ALTER TABLE light_motion_period
    ADD CONSTRAINT light_motion_period_rgb_check CHECK (rgb ~ '^[0-9]{1,3},[0-9]{1,3},[0-9]{1,3}$');

CREATE INDEX light_motion_period_room_idx ON light_motion_period (room);
