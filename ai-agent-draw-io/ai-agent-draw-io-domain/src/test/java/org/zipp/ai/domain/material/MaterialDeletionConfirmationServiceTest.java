package org.zipp.ai.domain.material;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.material.model.valobj.MaterialDeletionImpact;
import org.zipp.ai.domain.material.service.MaterialDeletionConfirmationService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaterialDeletionConfirmationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void tokenIsBoundToExactImpactAndGeneration() {
        MaterialDeletionConfirmationService service = service(NOW);
        MaterialDeletionImpact impact = impact(3, List.of("citation_1"));
        String token = service.issue(impact).token();

        assertTrue(service.verifies(token, impact));
        assertFalse(service.verifies(token,
                impact(4, List.of("citation_1"))));
        assertFalse(service.verifies(token,
                impact(3, List.of("citation_2"))));
    }

    @Test
    void expiredOrTamperedTokenFailsClosed() {
        MaterialDeletionImpact impact = impact(3, List.of("citation_1"));
        String token = service(NOW).issue(impact).token();

        assertFalse(service(NOW.plus(Duration.ofMinutes(11))).verifies(token, impact));
        assertFalse(service(NOW).verifies(token + "x", impact));
    }

    private MaterialDeletionConfirmationService service(Instant now) {
        return new MaterialDeletionConfirmationService(SECRET,
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(10));
    }

    private MaterialDeletionImpact impact(long generation, List<String> citations) {
        return new MaterialDeletionImpact("material_1", generation,
                List.of("version_1", "version_2"), List.of("version_1:revision_1"),
                List.of("diagram_1"), List.of("chartbook_1"), citations);
    }
}
