package org.zipp.ai.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationScopeMigrationTest {

    @Test
    void migrationCreatesDeterministicDefaultsAndOnlyUnambiguousAliases() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-27-migrate-conversation-scopes.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-27-migrate-conversation-scopes.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("CONCAT('conv_', SUBSTRING(SHA2"));
        assertTrue(sql.contains("HAVING COUNT(DISTINCT candidates.conversation_id) = 1"));
        assertTrue(sql.contains("UPDATE diagram_conversation_message"));
        assertTrue(sql.contains("SET m.conversation_id = c.id"));
        assertFalse(sql.contains("UPDATE material_scope_link"));
        assertFalse(sql.contains("&gt;"));
    }
}
