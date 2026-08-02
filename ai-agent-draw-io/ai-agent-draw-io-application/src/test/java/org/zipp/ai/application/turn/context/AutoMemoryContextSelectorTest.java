package org.zipp.ai.application.turn.context;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.memory.AutoMemory;
import org.zipp.ai.application.memory.AutoMemoryQueryPort;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.AutoMemoryVectorDocument;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchHit;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchPort;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchQuery;
import org.zipp.ai.application.turn.TurnKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryContextSelectorTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void semanticOrderIsPinnedWhileChartbookOverridesTheSameDecisionKey() {
        AutoMemory chartbook = memory(
                "memory-chartbook", AutoMemoryScope.chartbook("owner-1", "book-1"),
                "labels", "Use project labels", 2);
        AutoMemory globalSameKey = memory(
                "memory-global-labels", AutoMemoryScope.user("owner-1"),
                "labels", "Use global labels", 3);
        AutoMemory globalTheme = memory(
                "memory-global-theme", AutoMemoryScope.user("owner-1"),
                "theme", "Use a blue theme", 4);
        FakeAuthority authority = new FakeAuthority(List.of(
                chartbook, globalSameKey, globalTheme));
        FakeVectors vectors = new FakeVectors(List.of(
                vectorId(globalTheme), vectorId(chartbook), vectorId(globalSameKey)));
        AutoMemoryContextSelector selector = selector(authority, vectors, 4, 6_000);

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-global-theme", "memory-chartbook"), selected.references()
                .stream().map(AutoMemoryContextSelection.Reference::memoryId).toList());
        assertEquals(List.of(AutoMemoryContext.Scope.USER, AutoMemoryContext.Scope.CHARTBOOK),
                selected.context().entries().stream().map(AutoMemoryContext.Entry::scope).toList());
        assertFalse(selected.context().prioritizedEntries().stream()
                .anyMatch(value -> value.contains("Use global labels")));
        assertEquals(
                java.util.Set.of(AutoMemoryVectorDocument.CandidateKind.CURRENT),
                vectors.query.kinds());
        assertEquals(
                java.util.Set.of(AutoMemoryVectorDocument.CandidateState.ACTIVE),
                vectors.query.states());
    }

    @Test
    void vectorFailureReturnsTheStableSqlBaseline() {
        AutoMemory chartbook = memory(
                "memory-chartbook", AutoMemoryScope.chartbook("owner-1", "book-1"),
                "layout", "Use vertical layout", 2);
        AutoMemory user = memory(
                "memory-user", AutoMemoryScope.user("owner-1"),
                "labels", "Use concise labels", 3);
        FakeAuthority authority = new FakeAuthority(List.of(chartbook, user));
        FakeVectors vectors = new FakeVectors(new IllegalStateException("vector unavailable"));

        AutoMemoryContextSelection selected = selector(authority, vectors, 4, 6_000)
                .select(query());

        assertEquals(List.of("memory-chartbook", "memory-user"), selected.references()
                .stream().map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void successfulSemanticRecallFiltersWeakHitsWithoutSqlPadding() {
        AutoMemory relevant = memory(
                "memory-relevant", AutoMemoryScope.user("owner-1"),
                "labels", "Use concise labels", 2);
        AutoMemory weak = memory(
                "memory-weak", AutoMemoryScope.user("owner-1"),
                "theme", "Use a blue theme", 2);
        FakeAuthority authority = new FakeAuthority(List.of(relevant, weak));
        FakeVectors vectors = FakeVectors.scored(List.of(
                new AutoMemoryVectorSearchHit(vectorId(relevant), 0.82d),
                new AutoMemoryVectorSearchHit(vectorId(weak), 0.41d)));
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                vectors,
                new AutoMemoryContextSelector.SemanticPolicy(0.70d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-relevant"), selected.references().stream()
                .map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void successfulSemanticRecallMaySelectNoMemory() {
        AutoMemory baseline = memory(
                "memory-baseline", AutoMemoryScope.user("owner-1"),
                "theme", "Use a blue theme", 2);
        FakeAuthority authority = new FakeAuthority(List.of(baseline));

        AutoMemoryContextSelection selected = selector(
                authority, new FakeVectors(List.of()), 4, 6_000).select(query());

        assertTrue(selected.references().isEmpty());
    }

    @Test
    void uncertainTopHitIsRejectedInsteadOfInjected() {
        AutoMemory top = memory(
                "memory-top", AutoMemoryScope.user("owner-1"),
                "api-border", "Use navy API borders", 2);
        AutoMemory runnerUp = memory(
                "memory-runner-up", AutoMemoryScope.user("owner-1"),
                "queue-color", "Use pale blue queues", 2);
        FakeAuthority authority = new FakeAuthority(List.of(top, runnerUp));
        FakeVectors vectors = FakeVectors.scored(List.of(
                new AutoMemoryVectorSearchHit(vectorId(top), 0.828d),
                new AutoMemoryVectorSearchHit(vectorId(runnerUp), 0.814d)));
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                vectors,
                new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertTrue(selected.references().isEmpty());
    }

    @Test
    void closeHitsAreRetainedWhenBothIndependentlyClearTheScoreFloor() {
        AutoMemory first = memory(
                "memory-first", AutoMemoryScope.user("owner-1"),
                "queue-color", "Use pale blue queues", 2);
        AutoMemory second = memory(
                "memory-second", AutoMemoryScope.user("owner-1"),
                "cache-color", "Use teal caches", 2);
        AutoMemory weak = memory(
                "memory-weak", AutoMemoryScope.user("owner-1"),
                "canvas-color", "Use a white canvas", 2);
        FakeAuthority authority = new FakeAuthority(List.of(first, second, weak));
        FakeVectors vectors = FakeVectors.scored(List.of(
                new AutoMemoryVectorSearchHit(vectorId(first), 0.827d),
                new AutoMemoryVectorSearchHit(vectorId(second), 0.821d),
                new AutoMemoryVectorSearchHit(vectorId(weak), 0.810d)));
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                vectors,
                new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-first", "memory-second"), selected.references().stream()
                .map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void relativeWindowExcludesStrongHitsFarBehindTheTopCandidate() {
        AutoMemory relevant = memory(
                "memory-relevant", AutoMemoryScope.user("owner-1"),
                "owner-position", "Place owner badges at the top", 2);
        AutoMemory related = memory(
                "memory-related", AutoMemoryScope.user("owner-1"),
                "owner-color", "Use blue owner badges", 2);
        FakeAuthority authority = new FakeAuthority(List.of(relevant, related));
        FakeVectors vectors = FakeVectors.scored(List.of(
                new AutoMemoryVectorSearchHit(vectorId(relevant), 0.89d),
                new AutoMemoryVectorSearchHit(vectorId(related), 0.84d)));
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                vectors,
                new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d, 0.03d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-relevant"), selected.references().stream()
                .map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void ambiguousBroadCohortIsRejectedInsteadOfTruncated() {
        List<AutoMemory> memories = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> memory(
                        "memory-" + index,
                        AutoMemoryScope.user("owner-1"),
                        "label-rule-" + index,
                        "Use label rule " + index,
                        2))
                .toList();
        FakeAuthority authority = new FakeAuthority(memories);
        List<AutoMemoryVectorSearchHit> hits = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> new AutoMemoryVectorSearchHit(
                        vectorId(memories.get(index)), 0.829d - index * 0.001d))
                .toList();
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                FakeVectors.scored(hits),
                new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d, 0.03d),
                new AutoMemoryContextSelector.Budget(8, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertTrue(selected.references().isEmpty());
    }

    @Test
    void plannedIntentsAreGatedIndependentlyWithoutBroadOriginalPadding() {
        AutoMemory labels = memory(
                "memory-labels", AutoMemoryScope.user("owner-1"),
                "label-style", "Italicize database labels", 2);
        AutoMemory endpoints = memory(
                "memory-endpoints", AutoMemoryScope.user("owner-1"),
                "endpoint-badge", "Add globe badges to public endpoints", 2);
        FakeAuthority authority = new FakeAuthority(List.of(labels, endpoints));
        List<String> searches = new ArrayList<>();
        AutoMemoryVectorSearchPort vectors = (vectorQuery, topK) -> {
            searches.add(vectorQuery.userContent());
            return switch (vectorQuery.userContent()) {
                case "database label style" -> List.of(
                        new AutoMemoryVectorSearchHit(vectorId(labels), 0.91d));
                case "public endpoint badge style" -> List.of(
                        new AutoMemoryVectorSearchHit(vectorId(endpoints), 0.90d));
                default -> List.of();
            };
        };
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                vectors,
                ignored -> List.of("database label style", "public endpoint badge style"),
                new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d, 0.03d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-labels", "memory-endpoints"), selected.references()
                .stream().map(AutoMemoryContextSelection.Reference::memoryId).toList());
        assertEquals(List.of("database label style", "public endpoint badge style"), searches);
    }

    @Test
    void plannedFacetsHydrateCandidatePoolsOnceAndSelectOneWinnerEach() {
        AutoMemory lane = memory(
                "memory-lane", AutoMemoryScope.chartbook("owner-1", "book-1"),
                "lane-position", "Place worker lanes at the bottom", 2);
        AutoMemory laneNoise = memory(
                "memory-lane-noise", AutoMemoryScope.user("owner-1"),
                "queue-position", "Place queues at the bottom", 2);
        AutoMemory incident = memory(
                "memory-incident", AutoMemoryScope.chartbook("owner-1", "book-1"),
                "incident-color", "Use orange incident nodes", 2);
        AutoMemory colorNoise = memory(
                "memory-color-noise", AutoMemoryScope.chartbook("owner-1", "book-1"),
                "warning-color", "Use maroon warning nodes", 2);
        FakeAuthority authority = new FakeAuthority(List.of(
                lane, laneNoise, incident, colorNoise));
        AutoMemoryVectorSearchPort vectors = (vectorQuery, topK) -> switch (
                vectorQuery.userContent()) {
            case "worker lanes at the bottom" -> List.of(
                    new AutoMemoryVectorSearchHit(vectorId(lane), 0.816d),
                    new AutoMemoryVectorSearchHit(vectorId(laneNoise), 0.805d));
            case "orange incident nodes" -> List.of(
                    new AutoMemoryVectorSearchHit(vectorId(incident), 0.837d),
                    new AutoMemoryVectorSearchHit(vectorId(colorNoise), 0.825d));
            default -> List.of();
        };
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                vectors,
                ignored -> List.of("worker lanes at the bottom", "orange incident nodes"),
                new AutoMemoryContextSelector.SemanticPolicy(
                        0.82d, 0.02d, 0.03d, 0.80d, 0.01d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-lane", "memory-incident"), selected.references()
                .stream().map(AutoMemoryContextSelection.Reference::memoryId).toList());
        assertEquals(1, authority.hydrationCalls);
    }

    @Test
    void plannedFacetAcceptsARelevantCrossLanguageHitAboveTheFacetFloor() {
        AutoMemory labels = memory(
                "memory-labels", AutoMemoryScope.user("owner-1"),
                "node-label-brevity", "Keep node labels short", 2);
        AutoMemory unrelated = memory(
                "memory-unrelated", AutoMemoryScope.user("owner-1"),
                "alert-node-color", "Use coral alert nodes", 2);
        FakeAuthority authority = new FakeAuthority(List.of(labels, unrelated));
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                (vectorQuery, topK) -> vectorQuery.userContent().equals("我平时使用的标签风格")
                        ? List.of(
                                new AutoMemoryVectorSearchHit(vectorId(labels), 0.787d),
                                new AutoMemoryVectorSearchHit(vectorId(unrelated), 0.774d))
                        : List.of(),
                ignored -> List.of("我平时使用的标签风格", "我平时使用的告警颜色"),
                new AutoMemoryContextSelector.SemanticPolicy(
                        0.82d, 0.02d, 0.03d, 0.80d, 0.78d, 0.01d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-labels"), selected.references().stream()
                .map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void plannedFacetsAssignDifferentDecisionKeysWhenOneCandidateTopsBoth() {
        AutoMemory color = memory(
                "memory-color", AutoMemoryScope.user("owner-1"),
                "alarm-node-color", "Use coral orange alarm nodes", 2);
        AutoMemory labels = memory(
                "memory-labels", AutoMemoryScope.user("owner-1"),
                "node-label-length", "Keep node labels short", 2);
        FakeAuthority authority = new FakeAuthority(List.of(color, labels));
        AutoMemoryVectorSearchPort vectors = (vectorQuery, topK) -> switch (
                vectorQuery.userContent()) {
            case "my usual alarm node color" -> List.of(
                    new AutoMemoryVectorSearchHit(vectorId(color), 0.845d),
                    new AutoMemoryVectorSearchHit(vectorId(labels), 0.779d));
            case "my usual alarm label style" -> List.of(
                    new AutoMemoryVectorSearchHit(vectorId(color), 0.805d),
                    new AutoMemoryVectorSearchHit(vectorId(labels), 0.768d));
            default -> List.of();
        };
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                vectors,
                ignored -> List.of(
                        "my usual alarm node color", "my usual alarm label style"),
                new AutoMemoryContextSelector.SemanticPolicy(
                        0.82d, 0.02d, 0.03d, 0.80d, 0.76d, 0.01d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-color", "memory-labels"), selected.references().stream()
                .map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void plannedFacetsPreserveAnExplicitReferenceFromTheOriginalRequest() {
        AutoMemory color = memory(
                "memory-color", AutoMemoryScope.user("owner-1"),
                "alarm-node-color", "Use coral orange alarm nodes", 2);
        AutoMemory labels = memory(
                "memory-labels", AutoMemoryScope.user("owner-1"),
                "node-label-length", "Keep node labels short", 2);
        FakeAuthority authority = new FakeAuthority(List.of(color, labels));
        AutoMemoryVectorSearchPort vectors = (vectorQuery, topK) -> switch (
                vectorQuery.userContent()) {
            case "alert node color" -> List.of(
                    new AutoMemoryVectorSearchHit(vectorId(color), 0.846d),
                    new AutoMemoryVectorSearchHit(vectorId(labels), 0.764d));
            case "alert node label style" -> List.of(
                    new AutoMemoryVectorSearchHit(vectorId(color), 0.827d),
                    new AutoMemoryVectorSearchHit(vectorId(labels), 0.788d));
            default -> List.of();
        };
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                vectors,
                ignored -> List.of("alert node color", "alert node label style"),
                new AutoMemoryContextSelector.SemanticPolicy(
                        0.82d, 0.02d, 0.03d, 0.80d, 0.76d, 0.01d),
                new AutoMemoryContextSelector.Budget(4, 6_000));
        AutoMemoryContextQuery explicitQuery = new AutoMemoryContextQuery(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "book-1",
                "沿用我平时的告警节点颜色与标签风格");

        AutoMemoryContextSelection selected = selector.select(explicitQuery);

        assertEquals(List.of("memory-color", "memory-labels"), selected.references().stream()
                .map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void plannedOperationalFacetKeepsTheStrictFacetFloor() {
        AutoMemory position = memory(
                "memory-position", AutoMemoryScope.user("owner-1"),
                "sender-position", "Keep senders on the left", 2);
        FakeAuthority authority = new FakeAuthority(List.of(position));
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                (vectorQuery, topK) -> List.of(
                        new AutoMemoryVectorSearchHit(vectorId(position), 0.79d)),
                ignored -> List.of("move this node left", "export once"),
                new AutoMemoryContextSelector.SemanticPolicy(
                        0.82d, 0.02d, 0.03d, 0.80d, 0.78d, 0.01d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        assertTrue(selector.select(query()).empty());
    }

    @Test
    void plannedFacetDropsANearTieAcrossDifferentDecisionKeys() {
        AutoMemory first = memory(
                "memory-first", AutoMemoryScope.user("owner-1"),
                "notification-color", "Use indigo notification nodes", 2);
        AutoMemory second = memory(
                "memory-second", AutoMemoryScope.user("owner-1"),
                "outage-color", "Use violet outage nodes", 2);
        FakeAuthority authority = new FakeAuthority(List.of(first, second));
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                (vectorQuery, topK) -> List.of(
                        new AutoMemoryVectorSearchHit(vectorId(first), 0.826d),
                        new AutoMemoryVectorSearchHit(vectorId(second), 0.818d)),
                ignored -> List.of("first facet", "second facet"),
                new AutoMemoryContextSelector.SemanticPolicy(
                        0.82d, 0.02d, 0.03d, 0.80d, 0.01d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        assertTrue(selector.select(query()).empty());
    }

    @Test
    void plannedFacetFallsThroughAnInvalidVectorIdentityInsideItsBoundedPool() {
        AutoMemory relevant = memory(
                "memory-relevant", AutoMemoryScope.user("owner-1"),
                "labels", "Use concise labels", 2);
        AutoMemory absent = memory(
                "memory-absent", AutoMemoryScope.user("owner-1"),
                "other-labels", "Use verbose labels", 2);
        FakeAuthority authority = new FakeAuthority(List.of(relevant));
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                (vectorQuery, topK) -> List.of(
                        new AutoMemoryVectorSearchHit(vectorId(absent), 0.84d),
                        new AutoMemoryVectorSearchHit(vectorId(relevant), 0.83d)),
                ignored -> List.of("first facet", "second facet"),
                new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d, 0.03d, 0.80d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-relevant"), selected.references().stream()
                .map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void plannedFacetPrefersTheCurrentChartbookForTheSameDecisionKey() {
        AutoMemory global = memory(
                "memory-global", AutoMemoryScope.user("owner-1"),
                "warning-color", "Use yellow warnings", 2);
        AutoMemory chartbook = memory(
                "memory-chartbook", AutoMemoryScope.chartbook("owner-1", "book-1"),
                "warning-color", "Use plum warnings", 2);
        FakeAuthority authority = new FakeAuthority(List.of(global, chartbook));
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                (vectorQuery, topK) -> List.of(
                        new AutoMemoryVectorSearchHit(vectorId(global), 0.90d),
                        new AutoMemoryVectorSearchHit(vectorId(chartbook), 0.88d)),
                ignored -> List.of("warning color", "warning color for this book"),
                new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d, 0.03d, 0.80d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-chartbook"), selected.references().stream()
                .map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void emptyFacetPoolsUseTheOriginalStrictQueryAsFallback() {
        AutoMemory relevant = memory(
                "memory-relevant", AutoMemoryScope.user("owner-1"),
                "labels", "Use concise labels", 2);
        FakeAuthority authority = new FakeAuthority(List.of(relevant));
        List<String> searches = new ArrayList<>();
        AutoMemoryVectorSearchPort vectors = (vectorQuery, topK) -> {
            searches.add(vectorQuery.userContent());
            if (vectorQuery.userContent().equals(query().userContent())) {
                return List.of(new AutoMemoryVectorSearchHit(vectorId(relevant), 0.90d));
            }
            return List.of(new AutoMemoryVectorSearchHit(vectorId(relevant), 0.79d));
        };
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                vectors,
                ignored -> List.of("first facet", "second facet"),
                new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d, 0.03d, 0.80d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-relevant"), selected.references().stream()
                .map(AutoMemoryContextSelection.Reference::memoryId).toList());
        assertEquals(List.of("first facet", "second facet", query().userContent()), searches);
    }

    @Test
    void plannerCancellationIsNotConvertedIntoOriginalQueryFallback() {
        FakeAuthority authority = new FakeAuthority(List.of());
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                (vectorQuery, topK) -> List.of(),
                ignored -> {
                    throw new CancellationException("cancelled");
                },
                new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d, 0.03d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        assertThrows(CancellationException.class, () -> selector.select(query()));
    }

    @Test
    void oneFailedPlannedQueryDoesNotDiscardAnotherSuccessfulIntent() {
        AutoMemory endpoints = memory(
                "memory-endpoints", AutoMemoryScope.user("owner-1"),
                "endpoint-badge", "Add globe badges to public endpoints", 2);
        FakeAuthority authority = new FakeAuthority(List.of(endpoints));
        AutoMemoryVectorSearchPort vectors = (vectorQuery, topK) -> {
            if (vectorQuery.userContent().equals("unavailable intent")) {
                throw new IllegalStateException("one embedding call failed");
            }
            if (vectorQuery.userContent().equals("public endpoint badge style")) {
                return List.of(new AutoMemoryVectorSearchHit(vectorId(endpoints), 0.90d));
            }
            return List.of();
        };
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                vectors,
                ignored -> List.of("unavailable intent", "public endpoint badge style"),
                new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d, 0.03d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-endpoints"), selected.references()
                .stream().map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void allPlannedSearchesFailBackToTheStableSqlBaseline() {
        AutoMemory baseline = memory(
                "memory-baseline", AutoMemoryScope.user("owner-1"),
                "labels", "Use concise labels", 2);
        FakeAuthority authority = new FakeAuthority(List.of(baseline));
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                (vectorQuery, topK) -> {
                    throw new IllegalStateException("vector unavailable");
                },
                ignored -> List.of("first intent", "second intent"),
                new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d, 0.03d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-baseline"), selected.references()
                .stream().map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void plannerFailureKeepsTheOriginalSemanticQueryAvailable() {
        AutoMemory relevant = memory(
                "memory-relevant", AutoMemoryScope.user("owner-1"),
                "labels", "Use concise labels", 2);
        FakeAuthority authority = new FakeAuthority(List.of(relevant));
        AutoMemoryContextSelector selector = new AutoMemoryContextSelector(
                authority,
                authority,
                (vectorQuery, topK) -> List.of(
                        new AutoMemoryVectorSearchHit(vectorId(relevant), 0.90d)),
                ignored -> {
                    throw new IllegalStateException("planner unavailable");
                },
                new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d, 0.03d),
                new AutoMemoryContextSelector.Budget(4, 6_000));

        AutoMemoryContextSelection selected = selector.select(query());

        assertEquals(List.of("memory-relevant"), selected.references()
                .stream().map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void totalCharacterBudgetSkipsEntriesThatDoNotFit() {
        AutoMemory first = memory(
                "memory-first", AutoMemoryScope.user("owner-1"),
                "first", "A".repeat(210), 1);
        AutoMemory second = memory(
                "memory-second", AutoMemoryScope.user("owner-1"),
                "second", "B".repeat(210), 1);
        FakeAuthority authority = new FakeAuthority(List.of(first, second));

        AutoMemoryContextSelection selected = selector(authority, null, 4, 256).select(query());

        assertEquals(List.of("memory-first"), selected.references().stream()
                .map(AutoMemoryContextSelection.Reference::memoryId).toList());
    }

    @Test
    void materializationRejectsAChangedPinnedVersion() {
        AutoMemory original = memory(
                "memory-user", AutoMemoryScope.user("owner-1"),
                "labels", "Use concise labels", 3);
        FakeAuthority authority = new FakeAuthority(List.of(original));
        AutoMemoryContextSelector selector = selector(authority, null, 4, 6_000);
        AutoMemoryContextSelection selected = selector.select(query());
        authority.replace(memory(
                "memory-user", AutoMemoryScope.user("owner-1"),
                "labels", "Use very concise labels", 4));

        Optional<AutoMemoryContextSelection> materialized = selector.materialize(
                query(), selected.references());

        assertTrue(materialized.isEmpty());
    }

    private static AutoMemoryContextSelector selector(
            FakeAuthority authority,
            AutoMemoryVectorSearchPort vectors,
            int maxEntries,
            int maxCharacters
    ) {
        AutoMemoryContextSelector.Budget budget =
                new AutoMemoryContextSelector.Budget(maxEntries, maxCharacters);
        return vectors == null
                ? new AutoMemoryContextSelector(authority, authority, budget)
                : new AutoMemoryContextSelector(
                        authority,
                        authority,
                        vectors,
                        new AutoMemoryContextSelector.SemanticPolicy(0.0d),
                        budget);
    }

    private static AutoMemoryContextQuery query() {
        return new AutoMemoryContextQuery(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "book-1",
                "Add a concise approval node");
    }

    private static AutoMemory memory(
            String id,
            AutoMemoryScope scope,
            String key,
            String text,
            long version
    ) {
        return new AutoMemory(
                id,
                scope,
                AutoMemoryType.PREFERENCE,
                key,
                key,
                text,
                AutoMemoryStatus.ACTIVE,
                0.9d,
                2,
                true,
                version,
                NOW,
                NOW);
    }

    private static String vectorId(AutoMemory memory) {
        return AutoMemoryVectorDocument.current(memory, 1).vectorId();
    }

    private static final class FakeAuthority
            implements AutoMemoryQueryPort, AutoMemoryContextHydrationPort {
        private final Map<String, AutoMemory> memories = new HashMap<>();
        private final List<String> order = new ArrayList<>();
        private int hydrationCalls;

        private FakeAuthority(List<AutoMemory> initial) {
            initial.forEach(memory -> {
                memories.put(memory.memoryId(), memory);
                order.add(memory.memoryId());
            });
        }

        private void replace(AutoMemory memory) {
            memories.put(memory.memoryId(), memory);
        }

        @Override
        public List<AutoMemory> recallActive(AutoMemoryScope scope, int limit) {
            return order.stream()
                    .map(memories::get)
                    .filter(memory -> memory.scope().equals(scope))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<AutoMemory> findConsolidationCandidates(AutoMemoryScope scope, int limit) {
            return List.of();
        }

        @Override
        public List<AutoMemory> hydrateActiveVectorMatches(
                AutoMemoryContextQuery query,
                List<String> rankedVectorIds
        ) {
            hydrationCalls++;
            List<AutoMemory> result = new ArrayList<>();
            for (String vectorId : rankedVectorIds) {
                AutoMemoryVectorDocument.currentMemoryIdFromVectorId(vectorId)
                        .map(memories::get)
                        .ifPresent(result::add);
            }
            return List.copyOf(result);
        }

        @Override
        public List<AutoMemory> loadActive(
                AutoMemoryContextQuery query,
                List<String> memoryIds
        ) {
            return memoryIds.stream().map(memories::get).filter(java.util.Objects::nonNull).toList();
        }
    }

    private static final class FakeVectors implements AutoMemoryVectorSearchPort {
        private final List<AutoMemoryVectorSearchHit> hits;
        private final RuntimeException failure;
        private AutoMemoryVectorSearchQuery query;

        private FakeVectors(List<String> hits) {
            this.hits = hits.stream()
                    .map(id -> new AutoMemoryVectorSearchHit(id, 1.0d))
                    .toList();
            this.failure = null;
        }

        private FakeVectors(
                List<AutoMemoryVectorSearchHit> hits,
                RuntimeException failure
        ) {
            this.hits = List.copyOf(hits);
            this.failure = failure;
        }

        private static FakeVectors scored(List<AutoMemoryVectorSearchHit> hits) {
            return new FakeVectors(hits, null);
        }

        private FakeVectors(RuntimeException failure) {
            this.hits = List.of();
            this.failure = failure;
        }

        @Override
        public List<AutoMemoryVectorSearchHit> search(
                AutoMemoryVectorSearchQuery query,
                int topK
        ) {
            this.query = query;
            if (failure != null) {
                throw failure;
            }
            return hits.subList(0, Math.min(topK, hits.size()));
        }
    }
}
