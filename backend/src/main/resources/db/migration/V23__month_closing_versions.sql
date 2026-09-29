-- H07.3: a closed month receives new versions. Version 1 is the closing itself; every later version is recorded
-- by a VERSION_GENERATED event written in the same transaction that makes it the version in force.
ALTER TABLE month_closing_events DROP CONSTRAINT month_closing_events_event_type_check;
ALTER TABLE month_closing_events ADD CONSTRAINT month_closing_events_event_type_check CHECK (
    (version_number = 1 AND event_type = 'MONTH_CLOSED')
    OR (version_number > 1 AND event_type = 'VERSION_GENERATED')
);
