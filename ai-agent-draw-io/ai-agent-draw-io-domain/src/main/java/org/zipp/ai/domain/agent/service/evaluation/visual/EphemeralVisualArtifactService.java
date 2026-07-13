package org.zipp.ai.domain.agent.service.evaluation.visual;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local, owner-scoped image store. Restart or expiry deletes production pixels rather than retaining them. */
public class EphemeralVisualArtifactService {
    private static final Duration MAX_PRODUCTION_TTL = Duration.ofMinutes(15);
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public EphemeralVisualArtifactService() { this(Clock.systemUTC()); }
    public EphemeralVisualArtifactService(Clock clock) { this.clock = clock == null ? Clock.systemUTC() : clock; }

    public String put(Source source, String owner, boolean productionAccessApproved, Duration ttl,
                      IDiagramImageRenderer.RenderedDiagram image) {
        if (source == Source.PRODUCTION && !productionAccessApproved) throw new SecurityException("production image access is not approved");
        if (owner == null || owner.isBlank() || image == null || image.bytes().length == 0) throw new IllegalArgumentException("visual artifact input is incomplete");
        Duration requested = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(5) : ttl;
        Duration effective = source == Source.PRODUCTION && requested.compareTo(MAX_PRODUCTION_TTL) > 0 ? MAX_PRODUCTION_TTL : requested;
        String ref = "eva_" + UUID.randomUUID();
        entries.put(ref, new Entry(owner, source, clock.instant().plus(effective), image));
        return ref;
    }

    public Optional<IDiagramImageRenderer.RenderedDiagram> read(String ref, String actor) {
        purgeExpired(); Entry entry = entries.get(ref);
        if (entry == null) return Optional.empty();
        if (!entry.owner.equals(actor)) throw new SecurityException("visual artifact belongs to another actor");
        return Optional.of(entry.image);
    }

    public int purgeExpired() {
        Instant now = clock.instant(); int before = entries.size();
        entries.entrySet().removeIf(value -> !value.getValue().expiresAt.isAfter(now));
        return before - entries.size();
    }

    public enum Source { SYNTHETIC_EVAL, PRODUCTION }
    private record Entry(String owner, Source source, Instant expiresAt, IDiagramImageRenderer.RenderedDiagram image) { }
}
