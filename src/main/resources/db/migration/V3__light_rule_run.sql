-- The log of the rules engine (HAS-262): every command it sent, for the dashboard to show what the rules
-- of a room did. room is the name of a RoomName constant, as in light_room_rule; device_id the id of the
-- light in the device registry of database-service, so there is no foreign key, and the rows of a rule
-- that was removed stay. action is on or off, reason auto-off, motion-on or motion-off, error the code of
-- LightError when the light did not take the command (NULL when it did)
CREATE TABLE light_rule_run
(
    id        BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    room      VARCHAR(50)        NOT NULL,
    device_id BIGINT             NOT NULL,
    action    VARCHAR(10)        NOT NULL,
    reason    VARCHAR(20)        NOT NULL,
    error     VARCHAR(50),
    at        TIMESTAMP          NOT NULL
);

ALTER TABLE light_rule_run
    ADD CONSTRAINT light_rule_run_action_check CHECK (action IN ('on', 'off'));

-- the runs of a room in a span of time
CREATE INDEX light_rule_run_room_at_idx ON light_rule_run (room, at);
