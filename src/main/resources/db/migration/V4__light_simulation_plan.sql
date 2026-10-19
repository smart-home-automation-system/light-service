-- The plan of the presence simulation (HAS-263): data the administrator edits through the API, so it lives
-- here and not in the configuration of the service. One row per phase at most (evening, night, morning by
-- the name of the PhaseName constant); no rows - no simulation, an empty house only gets its lights off.
--
-- from_time / to_time: a time after noon is the evening the night starts, before noon the morning after;
-- the phase runs only between the dusk and the sunrise, each bound moved at random by up to jitter_min a
-- night. visits_min / visits_max NULL: a continuous phase (somebody is always somewhere); given: that many
-- short visits. PlanRules says the rest in words, these CHECKs repeat what one row can say
CREATE TABLE light_simulation_phase
(
    phase        VARCHAR(20) PRIMARY KEY NOT NULL,
    enabled      BOOLEAN                 NOT NULL,
    from_time    TIME                    NOT NULL,
    to_time      TIME                    NOT NULL,
    jitter_min   SMALLINT                NOT NULL,
    min_stay_min SMALLINT                NOT NULL,
    max_stay_min SMALLINT                NOT NULL,
    max_spots    SMALLINT                NOT NULL,
    visits_min   SMALLINT,
    visits_max   SMALLINT,
    updated_at   TIMESTAMP               NOT NULL
);

ALTER TABLE light_simulation_phase
    ADD CONSTRAINT light_simulation_phase_bounds_check CHECK (
        jitter_min BETWEEN 0 AND 60
            AND min_stay_min BETWEEN 1 AND 180
            AND max_stay_min BETWEEN min_stay_min AND 180
            AND max_spots BETWEEN 1 AND 3);

-- both or none; the IS NULL / IS NOT NULL are not redundant - a CHECK that comes out NULL lets the row in
ALTER TABLE light_simulation_phase
    ADD CONSTRAINT light_simulation_phase_visits_check CHECK (
        (visits_min IS NULL AND visits_max IS NULL)
            OR (visits_min IS NOT NULL AND visits_max IS NOT NULL AND visits_min BETWEEN 1 AND 6
            AND visits_max BETWEEN visits_min AND 6));

-- The spots of a phase in the order they were written: role stay, path or closing, the room by the name of
-- its RoomName constant (renaming one needs a migration of these rows), light_ids the registry ids of its
-- lights, "1,2,3". Gone with their phase
CREATE TABLE light_simulation_spot
(
    id        BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    phase     VARCHAR(20)        NOT NULL REFERENCES light_simulation_phase (phase) ON DELETE CASCADE,
    position  SMALLINT           NOT NULL,
    role      VARCHAR(10)        NOT NULL,
    room      VARCHAR(50)        NOT NULL,
    light_ids VARCHAR(500)       NOT NULL
);

ALTER TABLE light_simulation_spot
    ADD CONSTRAINT light_simulation_spot_role_check CHECK (role IN ('stay', 'path', 'closing'));

ALTER TABLE light_simulation_spot
    ADD CONSTRAINT light_simulation_spot_lights_check CHECK (light_ids ~ '^[0-9]+(,[0-9]+)*$');

CREATE INDEX light_simulation_spot_phase_idx ON light_simulation_spot (phase);
