-- Explicit offline maintenance: stop all session writers and cleanup first.
-- Run in a SQL client after inventory/backup; commit according to client settings.
-- Routine repository cleanup never executes this table-wide statement.
DELETE FROM light_session_attributes
WHERE NOT EXISTS (
    SELECT 1 FROM light_session S
    WHERE S.SESSION_ID = light_session_attributes.SESSION_ID
);
