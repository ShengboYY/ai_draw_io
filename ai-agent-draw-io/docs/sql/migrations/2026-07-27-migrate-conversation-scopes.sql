-- M3 compatibility migration for canonical Conversation scope.
-- Historical source/file/message scope keys are intentionally retained; consumers
-- read the canonical key plus a bounded immutable alias set during the horizon.

USE ai_draw_io;

-- One deterministic default Conversation per existing owner+Diagram keeps the old
-- diagram-wide history together without treating a restartable runtime session as
-- durable identity.
INSERT INTO conversation (id, owner_key, diagram_id, status, version)
SELECT CONCAT('conv_', SUBSTRING(SHA2(CONCAT('legacy:', d.user_id, ':', d.id), 256), 1, 59)),
       d.user_id, d.id, 'ACTIVE', 0
FROM diagram d
WHERE d.deleted = 0
  AND NOT EXISTS (
      SELECT 1
      FROM conversation c
      WHERE c.owner_key = d.user_id AND c.diagram_id = d.id
  );

-- Only unambiguous legacy keys are admitted. If one owner reused a session id
-- across diagrams, no alias is created and the resolver fails that raw reference
-- closed instead of choosing a conversation arbitrarily.
INSERT IGNORE INTO conversation_legacy_alias (owner_key, conversation_id, legacy_session_id)
SELECT candidates.owner_key, MIN(candidates.conversation_id), candidates.legacy_session_id
FROM (
    SELECT m.user_id AS owner_key, c.id AS conversation_id, m.session_id AS legacy_session_id
    FROM diagram_conversation_message m
    INNER JOIN conversation c
        ON c.owner_key = m.user_id AND c.diagram_id = m.diagram_id AND c.status = 'ACTIVE'
    WHERE m.session_id IS NOT NULL AND m.session_id <> ''
    UNION ALL
    SELECT s.owner_key, c.id, s.conversation_id
    FROM conversation_source_context s
    INNER JOIN conversation c
        ON c.owner_key = s.owner_key AND c.diagram_id = s.diagram_id AND c.status = 'ACTIVE'
    WHERE s.conversation_id IS NOT NULL AND s.conversation_id <> ''
) candidates
GROUP BY candidates.owner_key, candidates.legacy_session_id
HAVING COUNT(DISTINCT candidates.conversation_id) = 1;

-- New message reads can use the durable foreign-key identity immediately. The
-- original session_id remains available for audit and compatibility diagnostics.
UPDATE diagram_conversation_message m
INNER JOIN conversation c
    ON c.owner_key = m.user_id AND c.diagram_id = m.diagram_id AND c.status = 'ACTIVE'
SET m.conversation_id = c.id
WHERE m.conversation_id IS NULL;
