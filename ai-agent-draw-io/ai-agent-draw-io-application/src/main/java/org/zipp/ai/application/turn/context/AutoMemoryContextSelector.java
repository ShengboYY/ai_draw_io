package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.memory.AutoMemory;
import org.zipp.ai.application.memory.AutoMemoryQueryPort;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryVectorDocument;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchPort;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchHit;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchQuery;
import org.zipp.ai.application.memory.MemoryScopeType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Selects query-relevant Memory once and rebuilds only that pinned selection on retries. */
public final class AutoMemoryContextSelector {
    private static final int SQL_LIMIT_PER_SCOPE = 16;
    private static final int VECTOR_TOP_K = 16;
    private static final int MAX_PER_SCOPE = 8;
    private static final int MAX_SEMANTIC_COHORT = 4;

    private final AutoMemoryQueryPort memories;
    private final AutoMemoryContextHydrationPort hydration;
    private final AutoMemoryVectorSearchPort vectors;
    private final AutoMemoryRecallPlanner recallPlanner;
    private final SemanticPolicy semanticPolicy;
    private final Budget budget;

    public AutoMemoryContextSelector(
            AutoMemoryQueryPort memories,
            AutoMemoryContextHydrationPort hydration,
            Budget budget
    ) {
        this(memories, hydration, null, new SemanticPolicy(0.0d), budget);
    }

    public AutoMemoryContextSelector(
            AutoMemoryQueryPort memories,
            AutoMemoryContextHydrationPort hydration,
            AutoMemoryVectorSearchPort vectors,
            SemanticPolicy semanticPolicy,
            Budget budget
    ) {
        this(memories, hydration, vectors, AutoMemoryRecallPlanner.NONE, semanticPolicy, budget);
    }

    public AutoMemoryContextSelector(
            AutoMemoryQueryPort memories,
            AutoMemoryContextHydrationPort hydration,
            AutoMemoryVectorSearchPort vectors,
            AutoMemoryRecallPlanner recallPlanner,
            SemanticPolicy semanticPolicy,
            Budget budget
    ) {
        this.memories = Objects.requireNonNull(memories, "memories");
        this.hydration = Objects.requireNonNull(hydration, "hydration");
        this.vectors = vectors;
        this.recallPlanner = Objects.requireNonNull(recallPlanner, "recallPlanner");
        this.semanticPolicy = Objects.requireNonNull(semanticPolicy, "semanticPolicy");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public AutoMemoryContextSelection select(AutoMemoryContextQuery query) {
        Objects.requireNonNull(query, "query");
        List<AutoMemory> baseline = baseline(query);
        if (vectors == null) {
            return selection(selectBounded(baseline, baseline, query.chartbookId()));
        }
        SemanticRecall semantic = semantic(query);
        if (semantic.failed()) {
            return selection(selectBounded(baseline, baseline, query.chartbookId()));
        }
        List<AutoMemory> overrideSources = merge(semantic.overrideSources(), baseline);
        return selection(selectBounded(
                semantic.memories(), overrideSources, query.chartbookId()));
    }

    public Optional<AutoMemoryContextSelection> materialize(
            AutoMemoryContextQuery query,
            List<AutoMemoryContextSelection.Reference> references
    ) {
        Objects.requireNonNull(query, "query");
        List<AutoMemoryContextSelection.Reference> pinned = List.copyOf(
                references == null ? List.of() : references);
        if (pinned.isEmpty()) {
            return Optional.of(selection(List.of()));
        }
        List<String> ids = pinned.stream()
                .map(AutoMemoryContextSelection.Reference::memoryId)
                .toList();
        Map<String, AutoMemory> current = new HashMap<>();
        for (AutoMemory memory : hydration.loadActive(query, ids)) {
            current.put(memory.memoryId(), memory);
        }
        List<AutoMemory> ordered = new ArrayList<>(pinned.size());
        for (AutoMemoryContextSelection.Reference reference : pinned) {
            AutoMemory memory = current.get(reference.memoryId());
            if (memory == null || memory.version() != reference.version()) {
                return Optional.empty();
            }
            ordered.add(memory);
        }
        return Optional.of(selection(ordered));
    }

    private List<AutoMemory> baseline(AutoMemoryContextQuery query) {
        List<AutoMemory> result = new ArrayList<>(SQL_LIMIT_PER_SCOPE * 2);
        for (AutoMemoryScope scope : query.authorizedScopes()) {
            result.addAll(memories.recallActive(scope, SQL_LIMIT_PER_SCOPE));
        }
        return List.copyOf(result);
    }

    private SemanticRecall semantic(AutoMemoryContextQuery query) {
        List<String> facets = plannedFacets(query);
        if (facets.isEmpty()) {
            return originalSemantic(query, 0);
        }
        List<FacetCandidates> candidateGroups = new ArrayList<>(facets.size());
        int successfulQueries = 0;
        for (String facet : facets) {
            try {
                List<AutoMemoryVectorSearchHit> candidates = semanticPolicy.facetCandidates(
                        vectors.search(
                        AutoMemoryVectorSearchQuery.activeContext(
                                query.turn(), query.chartbookId(), facet),
                        VECTOR_TOP_K));
                if (!candidates.isEmpty()) {
                    candidateGroups.add(new FacetCandidates(candidates));
                }
                successfulQueries++;
            } catch (RuntimeException ignored) {
                // One failed facet must not discard independently successful semantic facets.
            }
        }
        if (candidateGroups.isEmpty()) {
            // The untouched request remains a strict fallback, not another facet candidate source.
            return originalSemantic(query, successfulQueries);
        }
        return hydrateFacets(query, candidateGroups);
    }

    private SemanticRecall originalSemantic(
            AutoMemoryContextQuery query,
            int precedingSuccessfulQueries
    ) {
        List<AutoMemoryVectorSearchHit> accepted;
        try {
            accepted = semanticPolicy.accept(vectors.search(
                    AutoMemoryVectorSearchQuery.activeContext(
                            query.turn(), query.chartbookId(), query.userContent()),
                    VECTOR_TOP_K));
        } catch (RuntimeException ignored) {
            return precedingSuccessfulQueries == 0
                    ? SemanticRecall.failure()
                    : SemanticRecall.empty();
        }
        try {
            List<AutoMemory> hydrated = hydration.hydrateActiveVectorMatches(
                    query,
                    accepted.stream().map(AutoMemoryVectorSearchHit::vectorId).toList());
            return SemanticRecall.available(hydrated, hydrated);
        } catch (RuntimeException ignored) {
            // MySQL authority failure cannot be replaced by unverified vector identities.
            return SemanticRecall.failure();
        }
    }

    private SemanticRecall hydrateFacets(
            AutoMemoryContextQuery query,
            List<FacetCandidates> candidateGroups
    ) {
        Set<String> vectorIds = new LinkedHashSet<>();
        candidateGroups.forEach(group -> group.hits().stream()
                .map(AutoMemoryVectorSearchHit::vectorId)
                .forEach(vectorIds::add));
        try {
            List<AutoMemory> hydrated = hydration.hydrateActiveVectorMatches(
                    query, List.copyOf(vectorIds));
            Map<String, AutoMemory> byMemoryId = new HashMap<>();
            hydrated.forEach(memory -> byMemoryId.put(memory.memoryId(), memory));
            Map<String, AutoMemory> winners = new LinkedHashMap<>();
            for (FacetCandidates group : candidateGroups) {
                AutoMemory winner = facetWinner(group, byMemoryId, query.chartbookId());
                if (winner != null) {
                    winners.putIfAbsent(winner.memoryId(), winner);
                }
            }
            return SemanticRecall.available(List.copyOf(winners.values()), hydrated);
        } catch (RuntimeException ignored) {
            // Candidate text and state must come from the MySQL authority.
            return SemanticRecall.failure();
        }
    }

    private AutoMemory facetWinner(
            FacetCandidates group,
            Map<String, AutoMemory> byMemoryId,
            String chartbookId
    ) {
        List<HydratedCandidate> active = group.hits().stream()
                .map(hit -> AutoMemoryVectorDocument.currentMemoryIdFromVectorId(hit.vectorId())
                        .map(byMemoryId::get)
                        .map(memory -> new HydratedCandidate(memory, hit.score())))
                .flatMap(Optional::stream)
                .toList();
        if (active.isEmpty()) {
            return null;
        }
        HydratedCandidate first = active.get(0);
        DecisionKey key = DecisionKey.from(first.memory());
        Optional<HydratedCandidate> chartbookOverride = active.stream()
                .filter(candidate -> first.memory().scope().type() == MemoryScopeType.USER
                        && chartbookId != null
                        && candidate.memory().scope().type() == MemoryScopeType.CHARTBOOK
                        && chartbookId.equals(candidate.memory().scope().scopeKey())
                        && key.equals(DecisionKey.from(candidate.memory())))
                .findFirst();
        if (chartbookOverride.isPresent()) {
            return chartbookOverride.get().memory();
        }
        Optional<HydratedCandidate> competitor = active.stream().skip(1)
                .filter(candidate -> !key.equals(DecisionKey.from(candidate.memory())))
                .findFirst();
        if (competitor.isPresent()
                && !semanticPolicy.acceptsFacetLead(
                        first.score(), competitor.get().score())) {
            return null;
        }
        return first.memory();
    }

    private List<String> plannedFacets(AutoMemoryContextQuery query) {
        List<String> planned;
        try {
            planned = recallPlanner.plan(query);
        } catch (RuntimeException failure) {
            if (failure instanceof CancellationException) {
                throw failure;
            }
            return List.of();
        }
        if (planned == null || planned.size() > AutoMemoryRecallPlanner.MAX_SUBQUERIES) {
            return List.of();
        }
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String value : planned) {
            if (value == null || value.isBlank() || value.length() > 500) {
                return List.of();
            }
            String trimmed = value.trim();
            unique.putIfAbsent(trimmed.toLowerCase(java.util.Locale.ROOT), trimmed);
        }
        return List.copyOf(unique.values());
    }

    private static List<AutoMemory> merge(
            List<AutoMemory> semantic,
            List<AutoMemory> baseline
    ) {
        Map<String, AutoMemory> unique = new LinkedHashMap<>();
        semantic.forEach(memory -> unique.putIfAbsent(memory.memoryId(), memory));
        baseline.forEach(memory -> unique.putIfAbsent(memory.memoryId(), memory));
        return List.copyOf(unique.values());
    }

    private List<AutoMemory> selectBounded(
            List<AutoMemory> ranked,
            List<AutoMemory> overrideSources,
            String chartbookId
    ) {
        Set<DecisionKey> chartbookOverrides = new HashSet<>();
        if (chartbookId != null) {
            overrideSources.stream()
                    .filter(memory -> memory.scope().type() == MemoryScopeType.CHARTBOOK
                            && chartbookId.equals(memory.scope().scopeKey()))
                    .map(DecisionKey::from)
                    .forEach(chartbookOverrides::add);
        }

        Map<MemoryScopeType, Integer> perScope = new HashMap<>();
        List<AutoMemory> selected = new ArrayList<>(budget.maxEntries());
        int usedCharacters = 0;
        for (AutoMemory memory : ranked) {
            if (memory.scope().type() == MemoryScopeType.USER
                    && chartbookOverrides.contains(DecisionKey.from(memory))) {
                continue;
            }
            int scopeCount = perScope.getOrDefault(memory.scope().type(), 0);
            String value = rendered(memory);
            int entryCharacters = value.length()
                    + (memory.scope().type() == MemoryScopeType.CHARTBOOK
                    ? "chartbookMemory=".length() : "userMemory=".length())
                    + 1; // Include the newline emitted by the Prompt renderer.
            if (scopeCount >= MAX_PER_SCOPE || selected.size() >= budget.maxEntries()) {
                continue;
            }
            if (usedCharacters + entryCharacters > budget.maxCharacters()) {
                continue;
            }
            selected.add(memory);
            perScope.put(memory.scope().type(), scopeCount + 1);
            usedCharacters += entryCharacters;
        }
        return List.copyOf(selected);
    }

    private static AutoMemoryContextSelection selection(List<AutoMemory> selected) {
        List<AutoMemoryContext.Entry> entries = selected.stream()
                .map(memory -> new AutoMemoryContext.Entry(
                        memory.scope().type() == MemoryScopeType.CHARTBOOK
                                ? AutoMemoryContext.Scope.CHARTBOOK
                                : AutoMemoryContext.Scope.USER,
                        rendered(memory)))
                .toList();
        List<AutoMemoryContextSelection.Reference> references = selected.stream()
                .map(memory -> new AutoMemoryContextSelection.Reference(
                        memory.memoryId(), memory.version()))
                .toList();
        long version = selected.stream().mapToLong(AutoMemory::version).max().orElse(0);
        return new AutoMemoryContextSelection(
                new AutoMemoryContext(entries), references, version, digest(selected));
    }

    private static String rendered(AutoMemory memory) {
        return memory.type() + "/" + memory.semanticKey() + ": " + memory.canonicalText();
    }

    private static String digest(List<AutoMemory> memories) {
        StringBuilder canonical = new StringBuilder("auto-memory-context-v1\n");
        for (AutoMemory memory : memories) {
            append(canonical, memory.memoryId());
            append(canonical, String.valueOf(memory.version()));
            append(canonical, memory.scope().type().name());
            append(canonical, memory.scope().scopeKey());
            append(canonical, memory.type().name());
            append(canonical, memory.semanticKey());
            append(canonical, memory.canonicalText());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append('\n');
    }

    public record Budget(int maxEntries, int maxCharacters) {
        public Budget {
            if (maxEntries < 1 || maxEntries > 16
                    || maxCharacters < 256 || maxCharacters > 24_000) {
                throw new IllegalArgumentException("invalid Auto Memory context budget");
            }
        }
    }

    public record SemanticPolicy(
            double minimumScore,
            double minimumLead,
            double maximumScoreDrop,
            double facetMinimumScore,
            double facetMinimumLead
    ) {
        public SemanticPolicy {
            if (!Double.isFinite(minimumScore)
                    || !Double.isFinite(minimumLead)
                    || !Double.isFinite(maximumScoreDrop)
                    || !Double.isFinite(facetMinimumScore)
                    || !Double.isFinite(facetMinimumLead)
                    || minimumLead < 0.0d
                    || maximumScoreDrop < 0.0d
                    || facetMinimumLead < 0.0d) {
                throw new IllegalArgumentException("invalid semantic acceptance policy");
            }
        }

        public SemanticPolicy(
                double minimumScore,
                double minimumLead,
                double maximumScoreDrop,
                double facetMinimumScore
        ) {
            this(minimumScore, minimumLead, maximumScoreDrop, facetMinimumScore, 0.0d);
        }

        public SemanticPolicy(
                double minimumScore,
                double minimumLead,
                double maximumScoreDrop
        ) {
            this(minimumScore, minimumLead, maximumScoreDrop, minimumScore, 0.0d);
        }

        public SemanticPolicy(double minimumScore, double minimumLead) {
            this(minimumScore, minimumLead, Double.MAX_VALUE);
        }

        public SemanticPolicy(double minimumScore) {
            this(minimumScore, 0.0d);
        }

        private List<AutoMemoryVectorSearchHit> accept(
                List<AutoMemoryVectorSearchHit> ranked
        ) {
            if (!acceptsQuery(ranked)) {
                return List.of();
            }
            return includeCandidates(ranked);
        }

        private List<AutoMemoryVectorSearchHit> facetCandidates(
                List<AutoMemoryVectorSearchHit> ranked
        ) {
            return ranked.stream()
                    // This is a candidate-only floor; MySQL and one-winner selection run afterwards.
                    .filter(hit -> hit.score() >= facetMinimumScore)
                    .limit(MAX_SEMANTIC_COHORT)
                    .toList();
        }

        private boolean acceptsFacetLead(double firstScore, double competitorScore) {
            return firstScore - competitorScore >= facetMinimumLead;
        }

        private boolean acceptsQuery(List<AutoMemoryVectorSearchHit> ranked) {
            if (ranked.isEmpty() || ranked.get(0).score() < minimumScore) {
                return false;
            }
            // A close runner-up is safe only when it independently clears the relevance floor.
            boolean secondIsStrong = ranked.size() > 1
                    && ranked.get(1).score() >= minimumScore;
            boolean firstHasClearLead = ranked.size() == 1
                    || ranked.get(0).score() - ranked.get(1).score() >= minimumLead;
            return secondIsStrong || firstHasClearLead;
        }

        private List<AutoMemoryVectorSearchHit> includeCandidates(
                List<AutoMemoryVectorSearchHit> ranked
        ) {
            double topScore = ranked.get(0).score();
            List<AutoMemoryVectorSearchHit> included = ranked.stream()
                    // The absolute floor accepts the query; the relative window bounds its cohort.
                    .filter(hit -> hit.score() >= minimumScore
                            && topScore - hit.score() <= maximumScoreDrop)
                    .toList();
            // A broad near-tie is ambiguous; truncating it would hide rather than remove pollution.
            return included.size() > MAX_SEMANTIC_COHORT ? List.of() : included;
        }
    }

    private record SemanticRecall(
            boolean failed,
            List<AutoMemory> memories,
            List<AutoMemory> overrideSources
    ) {
        private SemanticRecall {
            memories = List.copyOf(memories);
            overrideSources = List.copyOf(overrideSources);
        }

        private static SemanticRecall failure() {
            return new SemanticRecall(true, List.of(), List.of());
        }

        private static SemanticRecall empty() {
            return new SemanticRecall(false, List.of(), List.of());
        }

        private static SemanticRecall available(
                List<AutoMemory> memories,
                List<AutoMemory> overrideSources
        ) {
            return new SemanticRecall(false, memories, overrideSources);
        }
    }

    private record FacetCandidates(List<AutoMemoryVectorSearchHit> hits) {
        private FacetCandidates {
            hits = List.copyOf(hits);
        }
    }

    private record HydratedCandidate(AutoMemory memory, double score) {
    }

    private record DecisionKey(String type, String semanticKey) {
        private static DecisionKey from(AutoMemory memory) {
            return new DecisionKey(memory.type().name(), memory.semanticKey());
        }
    }
}
