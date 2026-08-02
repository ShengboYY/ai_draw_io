package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutoMemoryVectorDocumentTest {
    @Test
    void currentIdentityIsStableAcrossAuthorityRevisions() {
        AutoMemory memory = memory("Prefer concise labels");

        AutoMemoryVectorDocument first = AutoMemoryVectorDocument.current(memory, 1);
        AutoMemoryVectorDocument second = AutoMemoryVectorDocument.current(memory, 2);

        assertEquals(first.vectorId(), second.vectorId());
        assertFalse(first.vectorId().contains(memory.memoryId()));
        assertEquals("Node labels\nPrefer concise labels", first.retrievalText());
    }

    @Test
    void challengerIdentityChangesWithItsCanonicalValue() {
        AutoMemory memory = memory("Prefer concise labels");

        AutoMemoryVectorDocument concise = AutoMemoryVectorDocument.challenger(
                memory.memoryId(), memory.scope(), memory.title(),
                "Prefer concise labels", 3);
        AutoMemoryVectorDocument detailed = AutoMemoryVectorDocument.challenger(
                memory.memoryId(), memory.scope(), memory.title(),
                "Prefer detailed labels", 3);

        assertNotEquals(concise.vectorId(), detailed.vectorId());
        assertThrows(IllegalArgumentException.class, () -> new AutoMemoryVectorDocument(
                "invalid", memory.memoryId(), memory.scope(),
                AutoMemoryVectorDocument.CandidateKind.CURRENT,
                AutoMemoryVectorDocument.CandidateState.CONFLICTING,
                memory.title(), memory.canonicalText(), 3));
    }

    private static AutoMemory memory(String canonicalText) {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        return new AutoMemory(
                "memory-1",
                AutoMemoryScope.user("owner-1"),
                AutoMemoryType.PREFERENCE,
                "node-label-density",
                "Node labels",
                canonicalText,
                AutoMemoryStatus.ACTIVE,
                0.9d,
                2,
                false,
                3,
                now,
                now);
    }
}
