package org.zipp.ai.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.ChartbookMembershipContext;
import org.zipp.ai.application.turn.context.ChartbookProfileContext;
import org.zipp.ai.application.turn.context.AutoMemoryContext;
import org.zipp.ai.application.turn.context.AutoMemoryContextQuery;
import org.zipp.ai.application.turn.context.AutoMemoryContextSelection;
import org.zipp.ai.application.turn.context.AutoMemoryContextSelector;
import org.zipp.ai.application.turn.context.ContextCandidate;
import org.zipp.ai.application.turn.context.ContextCandidateLoadOutcome;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextMaterializationOutcome;
import org.zipp.ai.application.turn.context.ContextMaterializedSlices;
import org.zipp.ai.application.turn.context.ContextPinState;
import org.zipp.ai.application.turn.context.ContextRead;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextReadSetMaterializerPort;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.ConversationContextSummary;
import org.zipp.ai.application.turn.context.ConversationAttachmentView;
import org.zipp.ai.application.turn.context.ConversationContext;
import org.zipp.ai.application.turn.context.CurrentMessageAttachmentView;
import org.zipp.ai.application.turn.context.CurrentMessageAttachmentsContext;
import org.zipp.ai.application.turn.context.DegradedContext;
import org.zipp.ai.application.turn.context.TrustedCanvasContext;
import org.zipp.ai.application.turn.context.ContextCandidateQueryPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Transitional MySQL backend for the source-free Context lifecycle.
 *
 * <p>The current schema stores only the latest canvas row, so a pinned version is exact only while
 * that row remains unchanged. The materializer checks the same version and projection digest and
 * returns Retry after a concurrent mutation; it never substitutes a newer row for an old pin.</p>
 */
@Repository
public class MySqlTurnContextAdapter implements ContextCandidateQueryPort, ContextReadSetMaterializerPort {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(1);
    private static final int RECENT_TURN_MAX_CHARS = 4_000;
    private static final int CANVAS_SUMMARY_MAX_CHARS = 2_000;

    private static final String SELECT_EXECUTION = """
            SELECT current_attempt_id, attempt_epoch, diagram_id, request_message_id,
                   turn_input_binding_digest, attachment_binding_digest,
                   context_message_high_water, lease_expires_at,
                   CURRENT_TIMESTAMP(3) AS database_now, status
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            """;

    private static final String SELECT_DOMAIN_TEMPLATE = """
            SELECT d.id AS diagram_id, d.user_id, d.chartbook_id AS diagram_chartbook_id,
                   d.updated_at AS diagram_updated_at,
                   c.version AS canvas_version, %s AS current_xml,
                   c.content_hash AS canvas_content_hash, c.summary AS canvas_summary,
                   c.analysis_json AS canvas_analysis_json,
                   cb.id AS active_chartbook_id, cb.owner_key AS chartbook_owner_key,
                   cb.status AS chartbook_status, cb.updated_at AS chartbook_updated_at,
                   p.version AS chartbook_profile_version,
                   p.instructions AS chartbook_profile_instructions,
                   p.goal AS chartbook_profile_goal,
                   p.summary AS chartbook_profile_summary,
                   p.glossary_json AS chartbook_profile_glossary_json,
                   p.default_style_json AS chartbook_profile_default_style_json,
                   p.stable_constraints_json AS chartbook_profile_stable_constraints_json,
                   p.profile_state AS chartbook_profile_state,
                   %s
            FROM diagram d
            LEFT JOIN diagram_canvas_state c
                ON c.diagram_id = d.id AND c.user_id = d.user_id
            LEFT JOIN chartbook cb
                ON cb.id = d.chartbook_id AND cb.owner_key = d.user_id
            LEFT JOIN chartbook_profile p
                ON p.chartbook_id = cb.id AND p.owner_key = cb.owner_key
            WHERE d.id = ? AND d.user_id = ? AND d.deleted = 0
            """;

    /** V1.7 loads bounded Auto Memory through its application port, not an unbounded aggregate. */
    private static final String SELECTED_AUTO_MEMORY_COLUMNS = """
            0 AS auto_memory_version, JSON_ARRAY() AS auto_memory_json
            """;

    /** Rollback projection used before the Auto Memory migration is enabled. */
    private static final String LEGACY_MEMORY_COLUMNS = """
            COALESCE((SELECT MAX(m.version) FROM chartbook_memory m
                       WHERE m.owner_key = cb.owner_key AND m.chartbook_id = cb.id
                         AND m.status = 'ACTIVE'), 0) AS auto_memory_version,
            COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT(
                       'scopeType', 'CHARTBOOK', 'memoryType', 'PROJECT',
                       'semanticKey', m.decision_key, 'text', m.canonical_text))
                       FROM chartbook_memory m
                       WHERE m.owner_key = cb.owner_key AND m.chartbook_id = cb.id
                         AND m.status = 'ACTIVE'), JSON_ARRAY()) AS auto_memory_json
            """;

    /** Candidate reads projection columns only; materialization additionally verifies canvas XML. */
    private static final String SELECT_CANDIDATE_DOMAIN =
            SELECT_DOMAIN_TEMPLATE.formatted("NULL", SELECTED_AUTO_MEMORY_COLUMNS);
    private static final String SELECT_MATERIALIZED_DOMAIN =
            SELECT_DOMAIN_TEMPLATE.formatted("c.current_xml", SELECTED_AUTO_MEMORY_COLUMNS);
    private static final String SELECT_LEGACY_CANDIDATE_DOMAIN =
            SELECT_DOMAIN_TEMPLATE.formatted("NULL", LEGACY_MEMORY_COLUMNS);
    private static final String SELECT_LEGACY_MATERIALIZED_DOMAIN =
            SELECT_DOMAIN_TEMPLATE.formatted("c.current_xml", LEGACY_MEMORY_COLUMNS);

    private static final String SELECT_ATTACHMENTS = """
            SELECT a.conversation_file_ref, u.display_name, u.declared_mime
            FROM conversation_message_attachment a
            JOIN material_upload_session u
                ON u.id = a.conversation_file_ref AND u.owner_key = a.owner_key
               AND u.target_scope_type = 'CONVERSATION'
            WHERE a.owner_key = ? AND a.conversation_id = ? AND a.message_id = ?
              AND u.target_scope_key = ?
            ORDER BY a.attachment_order ASC
            """;

    private static final String SELECT_RECENT_MESSAGES_TEMPLATE = """
            SELECT id, conversation_id, role, content
            FROM diagram_conversation_message
            WHERE user_id = ? AND diagram_id = ?
              AND (conversation_id IN (%s) OR session_id IN (%s))
              AND message_status = 'COMMITTED'
              AND message_sequence IS NOT NULL AND message_sequence <= ?
            ORDER BY message_sequence DESC
            LIMIT 8
            """;

    private final JdbcOperations jdbc;
    private final MySqlConversationScopeKeyResolver conversationScopes;
    private final boolean autoMemoryEnabled;
    private final AutoMemoryContextSelector memoryContexts;

    public MySqlTurnContextAdapter(JdbcOperations jdbc) {
        this(jdbc, null, false, (AutoMemoryContextSelector) null);
    }

    public MySqlTurnContextAdapter(JdbcOperations jdbc,
                                   MySqlConversationScopeKeyResolver conversationScopes) {
        this(jdbc, conversationScopes, false, (AutoMemoryContextSelector) null);
    }

    public MySqlTurnContextAdapter(
            JdbcOperations jdbc,
            AutoMemoryContextSelector memoryContexts
    ) {
        this(jdbc, null, true, Objects.requireNonNull(memoryContexts, "memoryContexts"));
    }

    @Autowired
    public MySqlTurnContextAdapter(
            JdbcOperations jdbc,
            MySqlConversationScopeKeyResolver conversationScopes,
            @Value("${app.memory.auto-enabled:false}") boolean autoMemoryEnabled,
            ObjectProvider<AutoMemoryContextSelector> memoryContextSelectors
    ) {
        this(
                jdbc,
                conversationScopes,
                autoMemoryEnabled,
                autoMemoryEnabled ? memoryContextSelectors.getIfAvailable() : null);
        if (autoMemoryEnabled && memoryContexts == null) {
            throw new IllegalStateException("Auto Memory context selector is required");
        }
    }

    private MySqlTurnContextAdapter(
            JdbcOperations jdbc,
            MySqlConversationScopeKeyResolver conversationScopes,
            boolean autoMemoryEnabled,
            AutoMemoryContextSelector memoryContexts
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.conversationScopes = conversationScopes;
        this.autoMemoryEnabled = autoMemoryEnabled;
        this.memoryContexts = memoryContexts;
    }

    @Override
    public ContextCandidateLoadOutcome loadCandidate(FencedAttempt attempt, UserTurnCommand command) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(command, "command");
        try {
            ExecutionCheck execution = checkExecution(attempt);
            if (!(execution instanceof ExecutionCheck.Valid valid)) {
                return candidateExecutionOutcome(execution, attempt.key());
            }
            if (!valid.row().diagramId().equals(command.diagramId())) {
                return unavailableCandidate(attempt.key(), TurnFailureCode.STALE_ATTEMPT);
            }
            DomainRow domain = findDomain(
                    autoMemoryEnabled ? SELECT_CANDIDATE_DOMAIN : SELECT_LEGACY_CANDIDATE_DOMAIN,
                    attempt.key().ownerKey(),
                    command.diagramId(),
                    false);
            if (domain == null) {
                return new ContextCandidateLoadOutcome.Revoked("DIAGRAM_NOT_AVAILABLE");
            }
            AutoMemoryContextSelection memorySelection = autoMemoryEnabled
                    ? memoryContexts.select(memoryQuery(attempt, command, domain))
                    : null;
            return new ContextCandidateLoadOutcome.Ready(
                    new ContextCandidate(candidateReadSet(
                            attempt.contextMessageHighWater(), domain, memorySelection)));
        } catch (DataAccessException exception) {
            return unavailableCandidate(attempt.key(), TurnFailureCode.TERMINAL_UNAVAILABLE);
        } catch (RuntimeException exception) {
            // Malformed rows fail closed at the persistence boundary instead of entering Context.
            return unavailableCandidate(attempt.key(), TurnFailureCode.TERMINAL_UNAVAILABLE);
        }
    }

    @Override
    public ContextMaterializationOutcome materialize(
            FencedAttempt attempt,
            UserTurnCommand command,
            ContextReadSet readSet
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(readSet, "readSet");
        try {
            if (readSet.messageHighWater() != attempt.contextMessageHighWater()) {
                return unavailableMaterialization(attempt, TurnFailureCode.STALE_ATTEMPT);
            }
            ExecutionCheck execution = checkExecution(attempt);
            if (!(execution instanceof ExecutionCheck.Valid valid)) {
                return materializationExecutionOutcome(execution, attempt.key());
            }
            if (!valid.row().diagramId().equals(command.diagramId())) {
                return unavailableMaterialization(attempt, TurnFailureCode.STALE_ATTEMPT);
            }
            DomainRow domain = findDomain(
                    autoMemoryEnabled
                            ? SELECT_MATERIALIZED_DOMAIN : SELECT_LEGACY_MATERIALIZED_DOMAIN,
                    attempt.key().ownerKey(),
                    command.diagramId(),
                    true);
            if (domain == null) {
                return new ContextMaterializationOutcome.Revoked("DIAGRAM_NOT_AVAILABLE");
            }

            SliceResolution<TrustedCanvasContext> canvasResolution =
                    materializeCanvas(readSet.summary(), domain);
            if (canvasResolution.retry()) {
                return new ContextMaterializationOutcome.Retry();
            }
            SliceResolution<ChartbookMembershipContext> membershipResolution =
                    materializeMembership(readSet.membership(), domain);
            if (membershipResolution.retry()) {
                return new ContextMaterializationOutcome.Retry();
            }
            SliceResolution<ChartbookProfileContext> profileResolution =
                    materializeProfile(readSet.profile(), domain);
            if (profileResolution.retry()) {
                return new ContextMaterializationOutcome.Retry();
            }
            SliceResolution<AutoMemoryContext> memoryResolution =
                    materializeMemory(
                            readSet.memory(), domain, attempt.key(), command,
                            readSet.memorySelection());
            if (memoryResolution.retry()) {
                return new ContextMaterializationOutcome.Retry();
            }

            List<CurrentMessageAttachmentView> attachments = materializeAttachments(
                    valid.row(), attempt.key(), command);
            ContextRead<ConversationContext> conversation = materializeConversation(
                    attempt, command, valid.row().requestMessageId());
            List<String> diagnosticCodes = new ArrayList<>(List.of(
                    "CONTEXT_BACKEND_TRANSITIONAL_LATEST_ROW",
                    "CONTEXT_MEMORY_ABSENT",
                    "CONTEXT_CLARIFICATION_ABSENT",
                    "CONTEXT_SELECTION_ABSENT"));
            diagnosticCodes.add(domain.hasProfile()
                    ? "CONTEXT_PROFILE_PROJECTED" : "CONTEXT_PROFILE_ABSENT");
            diagnosticCodes.add(readSet.memory().state() == ContextPinState.PINNED
                    ? "CONTEXT_MEMORY_PROJECTED" : "CONTEXT_MEMORY_ABSENT");
            ContextDiagnostics diagnostics = new ContextDiagnostics(diagnosticCodes);

            return new ContextMaterializationOutcome.Ready(new ContextMaterializedSlices(
                    new AvailableContext<>(
                            new CurrentMessageAttachmentsContext(
                                    requiredBindingDigest(valid.row()), attachments),
                            "turn_execution.attachment_binding_digest"),
                    new AbsentContext<>("NO_ACTIVE_CLARIFICATION_TABLE"),
                    canvasResolution.value(),
                    new AbsentContext<>("NO_DURABLE_SELECTION_BINDING"),
                    conversation,
                    membershipResolution.value(),
                    profileResolution.value(),
                    memoryResolution.value(),
                    diagnostics));
        } catch (DataAccessException exception) {
            return unavailableMaterialization(attempt, TurnFailureCode.TERMINAL_UNAVAILABLE);
        } catch (RuntimeException exception) {
            // Bounded context constructors and malformed database rows are fail-closed.
            return unavailableMaterialization(attempt, TurnFailureCode.TERMINAL_UNAVAILABLE);
        }
    }

    private ContextReadSet candidateReadSet(
            long messageHighWater,
            DomainRow domain,
            AutoMemoryContextSelection selection
    ) {
        ContextSlicePin summary = domain.hasCanvas()
                ? ContextSlicePin.pinned(
                        ContextSlice.SUMMARY,
                        "canvas:" + domain.diagramId(),
                        domain.canvasVersionValue(),
                        domain.canvasDigest())
                : ContextSlicePin.absent(ContextSlice.SUMMARY, "NO_CANVAS");
        ContextSlicePin membership = domain.hasActiveChartbook()
                ? ContextSlicePin.pinned(
                        ContextSlice.MEMBERSHIP,
                        "chartbook:" + domain.chartbookId() + ":diagram:" + domain.diagramId(),
                        domain.membershipRevision(),
                        domain.membershipDigest())
                : ContextSlicePin.absent(ContextSlice.MEMBERSHIP, "NO_ACTIVE_CHARTBOOK");
        ContextSlicePin profile = domain.hasProfile()
                ? ContextSlicePin.pinned(
                        ContextSlice.PROFILE,
                        "chartbook-profile:" + domain.chartbookId(),
                        domain.profileVersionValue(),
                        domain.profileDigest())
                : ContextSlicePin.absent(ContextSlice.PROFILE, "PROFILE_NOT_AVAILABLE");
        ContextSlicePin memory;
        if (autoMemoryEnabled) {
            memory = selection.empty()
                    ? ContextSlicePin.absent(ContextSlice.MEMORY, "NO_RELEVANT_AUTO_MEMORY")
                    : ContextSlicePin.pinned(
                            ContextSlice.MEMORY,
                            "auto-memory-selection:" + domain.ownerKey() + ":" + nonBlankOr(
                                    domain.chartbookId(), "no-chartbook"),
                            selection.version(),
                            selection.digest());
        } else {
            memory = domain.hasMemory()
                    ? ContextSlicePin.pinned(
                            ContextSlice.MEMORY,
                            "auto-memory:" + domain.ownerKey() + ":" + nonBlankOr(
                                    domain.chartbookId(), "no-chartbook"),
                            domain.memoryVersionValue(),
                            domain.memoryDigest())
                    : ContextSlicePin.absent(ContextSlice.MEMORY, "NO_ACTIVE_AUTO_MEMORY");
        }
        if (autoMemoryEnabled) {
            return ContextReadSet.createWithMemorySelection(
                    messageHighWater,
                    summary,
                    membership,
                    profile,
                    memory,
                    selection.references());
        }
        return ContextReadSet.create(
                1,
                messageHighWater,
                summary,
                membership,
                profile,
                memory);
    }

    private SliceResolution<TrustedCanvasContext> materializeCanvas(
            ContextSlicePin pin,
            DomainRow domain
    ) {
        if (pin.state() == ContextPinState.REVOKED) {
            return SliceResolution.exact(new DegradedContext<>(pin.reference()));
        }
        if (pin.state() == ContextPinState.DEGRADED) {
            return SliceResolution.exact(new DegradedContext<>(pin.reference()));
        }
        if (pin.state() == ContextPinState.ABSENT) {
            if (domain.hasCanvas()) {
                return SliceResolution.retrying();
            }
            return SliceResolution.exact(new AbsentContext<>(pin.reference()));
        }
        if (!domain.hasCanvas()
                || domain.canvasVersionValue() != pin.version()
                || !domain.canvasDigest().equals(pin.contentDigest())) {
            return SliceResolution.retrying();
        }
        // current_xml is the Canvas source of truth. Stored analysis/summary columns are only
        // derived caches and may have been written by an older mutation path.
        CanvasContextMetadata metadata = CanvasContextMetadata.fromXml(domain.currentXml());
        return SliceResolution.exact(new AvailableContext<>(new TrustedCanvasContext(
                metadata.nodeCount(),
                metadata.edgeCount(),
                metadata.summary(),
                domain.canvasVersionValue(),
                domain.canvasContentHash(),
                domain.currentXml()),
                pin.reference()));
    }

    private SliceResolution<ChartbookMembershipContext> materializeMembership(
            ContextSlicePin pin,
            DomainRow domain
    ) {
        if (pin.state() == ContextPinState.REVOKED || pin.state() == ContextPinState.DEGRADED) {
            return SliceResolution.exact(new DegradedContext<>(pin.reference()));
        }
        if (pin.state() == ContextPinState.ABSENT) {
            if (domain.hasActiveChartbook()) {
                return SliceResolution.retrying();
            }
            return SliceResolution.exact(new AbsentContext<>(pin.reference()));
        }
        if (!domain.hasActiveChartbook()
                || domain.membershipRevision() != pin.version()
                || !domain.membershipDigest().equals(pin.contentDigest())) {
            return SliceResolution.retrying();
        }
        return SliceResolution.exact(new AvailableContext<>(new ChartbookMembershipContext(
                domain.chartbookId(), domain.membershipRevision(), domain.chartbookStatusRevision()),
                pin.reference()));
    }

    private SliceResolution<ChartbookProfileContext> materializeProfile(
            ContextSlicePin pin,
            DomainRow domain
    ) {
        if (pin.state() == ContextPinState.REVOKED || pin.state() == ContextPinState.DEGRADED) {
            return SliceResolution.exact(new DegradedContext<>(pin.reference()));
        }
        if (pin.state() == ContextPinState.ABSENT) {
            if (domain.hasProfile()) {
                return SliceResolution.retrying();
            }
            return SliceResolution.exact(new AbsentContext<>(pin.reference()));
        }
        if (!domain.hasProfile()
                || domain.profileVersionValue() != pin.version()
                || !domain.profileDigest().equals(pin.contentDigest())) {
            return SliceResolution.retrying();
        }
        try {
            List<String> glossary = ChartbookProfilePersistenceCodec.parseMap(
                            domain.profileGlossaryJson()).entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toList();
            return SliceResolution.exact(new AvailableContext<>(new ChartbookProfileContext(
                    domain.profileInstructions(), domain.profileGoal(), domain.profileSummary(), glossary,
                    domain.profileDefaultStyleJson(),
                    ChartbookProfilePersistenceCodec.parseList(domain.profileStableConstraintsJson())),
                    pin.reference()));
        } catch (RuntimeException exception) {
            // A corrupt Profile degrades only this optional slice; plain generation may continue.
            return SliceResolution.exact(new DegradedContext<>("PROFILE_MALFORMED"));
        }
    }

    private SliceResolution<AutoMemoryContext> materializeMemory(
            ContextSlicePin pin,
            DomainRow domain,
            TurnKey turn,
            UserTurnCommand command,
            List<AutoMemoryContextSelection.Reference> references
    ) {
        if (pin.state() == ContextPinState.REVOKED || pin.state() == ContextPinState.DEGRADED) {
            return SliceResolution.exact(new DegradedContext<>(pin.reference()));
        }
        if (autoMemoryEnabled) {
            if (pin.state() == ContextPinState.ABSENT) {
                return references.isEmpty()
                        ? SliceResolution.exact(new AbsentContext<>(pin.reference()))
                        : SliceResolution.retrying();
            }
            Optional<AutoMemoryContextSelection> selected = memoryContexts.materialize(
                    memoryQuery(turn, command, domain), references);
            if (selected.isEmpty()
                    || selected.get().version() != pin.version()
                    || !selected.get().digest().equals(pin.contentDigest())) {
                return SliceResolution.retrying();
            }
            return SliceResolution.exact(new AvailableContext<>(
                    selected.get().context(), pin.reference()));
        }
        if (pin.state() == ContextPinState.ABSENT) {
            if (domain.hasMemory()) {
                return SliceResolution.retrying();
            }
            return SliceResolution.exact(new AbsentContext<>(pin.reference()));
        }
        if (!domain.hasMemory()
                || domain.memoryVersionValue() != pin.version()
                || !domain.memoryDigest().equals(pin.contentDigest())) {
            return SliceResolution.retrying();
        }
        try {
            var entries = JSON.parseArray(domain.memoryJson());
            java.util.Set<String> chartbookKeys = new java.util.HashSet<>();
            java.util.Set<String> userKeys = new java.util.HashSet<>();
            List<String> chartbookMemories = new ArrayList<>();
            List<String> userMemories = new ArrayList<>();
            for (Object value : entries) {
                JSONObject object = (JSONObject) value;
                String scopeType = bounded(object.getString("scopeType"), 16);
                String memoryType = bounded(object.getString("memoryType"), 16);
                String key = bounded(object.getString("semanticKey"), 128);
                String text = bounded(object.getString("text"), 1_000);
                boolean chartbook = "CHARTBOOK".equals(scopeType);
                boolean user = "USER".equals(scopeType);
                java.util.Set<String> keys = chartbook ? chartbookKeys : userKeys;
                if ((!chartbook && !user) || memoryType.isBlank() || key.isBlank()
                        || text.isBlank() || !keys.add(key)) {
                    // Conflicting or malformed Memory is never silently injected.
                    return SliceResolution.exact(new DegradedContext<>("MEMORY_CONFLICT_OR_MALFORMED"));
                }
                String entry = memoryType + "/" + key + ": " + text;
                List<String> target = chartbook ? chartbookMemories : userMemories;
                if (target.size() < 8) {
                    target.add(entry);
                }
            }
            return SliceResolution.exact(new AvailableContext<>(new AutoMemoryContext(
                            chartbookMemories, userMemories),
                    pin.reference()));
        } catch (RuntimeException exception) {
            return SliceResolution.exact(new DegradedContext<>("MEMORY_MALFORMED"));
        }
    }

    private AutoMemoryContextQuery memoryQuery(
            TurnKey turn,
            UserTurnCommand command,
            DomainRow domain
    ) {
        return new AutoMemoryContextQuery(
                turn,
                domain.hasActiveChartbook() ? domain.chartbookId() : null,
                command.content());
    }

    private AutoMemoryContextQuery memoryQuery(
            FencedAttempt attempt,
            UserTurnCommand command,
            DomainRow domain
    ) {
        String chartbookId = domain.hasActiveChartbook() ? domain.chartbookId() : null;
        String planningContextDigest = ModelInputBinding.digestOf(
                "auto-memory-recall-planning-v1", chartbookId, command.content());
        return new AutoMemoryContextQuery(
                attempt.key(),
                chartbookId,
                command.content(),
                ModelInputBinding.bound(
                        attempt.key(), planningContextDigest, attempt.inputBindingDigest()));
    }

    private List<CurrentMessageAttachmentView> materializeAttachments(
            ExecutionRow execution,
            TurnKey key,
            UserTurnCommand command
    ) {
        List<OpaqueConversationFileRef> expected = command.declarations().currentTurnAttachments();
        if (expected.isEmpty()) {
            return List.of();
        }
        if (execution.requestMessageId() == null) {
            throw new IllegalStateException("ATTACHMENT_BINDING_MESSAGE_MISSING");
        }
        List<String> scopeKeys = conversationScopes == null
                ? List.of(key.canonicalConversationId())
                : conversationScopes.readableScopeKeys(
                        new AuthenticatedActor(key.ownerKey(), key.ownerKey()),
                        key.canonicalConversationId(), command.diagramId()).allKeys();
        java.util.LinkedHashMap<String, AttachmentRow> byReference = new java.util.LinkedHashMap<>();
        for (String scopeKey : scopeKeys) {
            jdbc.query(
                    SELECT_ATTACHMENTS,
                    (resultSet, rowNum) -> new AttachmentRow(
                            resultSet.getString("conversation_file_ref"),
                            resultSet.getString("display_name"),
                            resultSet.getString("declared_mime")),
                    key.ownerKey(), key.canonicalConversationId(), execution.requestMessageId(), scopeKey)
                    .forEach(row -> byReference.putIfAbsent(row.reference(), row));
        }
        List<AttachmentRow> rows = List.copyOf(byReference.values());
        if (rows.size() != expected.size()) {
            throw new IllegalStateException("ATTACHMENT_BINDING_CHANGED");
        }
        List<CurrentMessageAttachmentView> values = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            AttachmentRow row = rows.get(index);
            if (!expected.get(index).value().equals(row.reference())) {
                throw new IllegalStateException("ATTACHMENT_BINDING_CHANGED");
            }
            values.add(new CurrentMessageAttachmentView(
                    expected.get(index),
                    bounded(nonBlankOr(row.declaredMime(), "application/octet-stream"), 128),
                    bounded(nonBlankOr(row.displayName(), row.reference()), 256)));
        }
        return List.copyOf(values);
    }

    private ContextRead<ConversationContext> materializeConversation(
            FencedAttempt attempt,
            UserTurnCommand command,
            Long currentRequestMessageId
    ) {
        List<String> scopeKeys = conversationScopes == null
                ? List.of(attempt.key().canonicalConversationId())
                : conversationScopes.readableScopeKeys(
                        new AuthenticatedActor(attempt.key().ownerKey(), attempt.key().ownerKey()),
                        attempt.key().canonicalConversationId(), command.diagramId()).allKeys();
        String placeholders = String.join(",", Collections.nCopies(scopeKeys.size(), "?"));
        List<Object> queryArgs = new ArrayList<>(2 + scopeKeys.size() * 2 + 1);
        queryArgs.add(attempt.key().ownerKey());
        queryArgs.add(command.diagramId());
        queryArgs.addAll(scopeKeys);
        queryArgs.addAll(scopeKeys);
        queryArgs.add(attempt.contextMessageHighWater());
        List<ConversationMessageRow> rows = new ArrayList<>(jdbc.query(
                SELECT_RECENT_MESSAGES_TEMPLATE.formatted(placeholders, placeholders),
                (resultSet, rowNum) -> new ConversationMessageRow(
                        nullableLong(resultSet, "id"),
                        resultSet.getString("conversation_id"),
                        resultSet.getString("role"),
                        resultSet.getString("content")),
                queryArgs.toArray()));
        if (rows.isEmpty()) {
            return new AbsentContext<>("NO_CANONICAL_MESSAGES_AT_HIGH_WATER");
        }
        List<ConversationAttachmentView> recentAttachments =
                command.declarations().currentTurnAttachments().isEmpty()
                        ? recentUserMessageAttachments(
                                attempt, rows, scopeKeys, currentRequestMessageId)
                        : List.of();
        Collections.reverse(rows);
        List<String> recentTurns = rows.stream()
                .map(row -> conversationTurn(row.role(), row.content()))
                .filter(Objects::nonNull)
                .toList();
        if (recentTurns.isEmpty()) {
            return new AbsentContext<>("NO_NONEMPTY_CANONICAL_MESSAGES_AT_HIGH_WATER");
        }
        return new AvailableContext<>(new ConversationContext(
                recentTurns, ConversationContextSummary.rebuild(
                        recentTurns, attempt.contextMessageHighWater()),
                recentAttachments),
                "diagram_conversation_message.canonical_high_water");
    }

    private List<ConversationAttachmentView> recentUserMessageAttachments(
            FencedAttempt attempt,
            List<ConversationMessageRow> messagesNewestFirst,
            List<String> scopeKeys,
            Long currentRequestMessageId
    ) {
        try {
            for (ConversationMessageRow message : messagesNewestFirst) {
                if (!"user".equalsIgnoreCase(message.role())
                        || message.id() == null
                        || message.conversationId() == null
                        || Objects.equals(message.id(), currentRequestMessageId)) {
                    continue;
                }
                java.util.LinkedHashMap<String, AttachmentRow> byReference =
                        new java.util.LinkedHashMap<>();
                for (String scopeKey : scopeKeys) {
                    jdbc.query(
                            SELECT_ATTACHMENTS,
                            (resultSet, rowNum) -> new AttachmentRow(
                                    resultSet.getString("conversation_file_ref"),
                                    resultSet.getString("display_name"),
                                    resultSet.getString("declared_mime")),
                            attempt.key().ownerKey(),
                            message.conversationId(),
                            message.id(),
                            scopeKey)
                            .forEach(row -> byReference.putIfAbsent(row.reference(), row));
                }
                if (!byReference.isEmpty()) {
                    // Only the nearest prior user message is exposed, with a hard metadata-only cap.
                    return byReference.values().stream()
                            .limit(8)
                            .map(row -> new ConversationAttachmentView(
                                    new OpaqueConversationFileRef(row.reference()),
                                    bounded(nonBlankOr(
                                            row.declaredMime(), "application/octet-stream"), 128),
                                    bounded(nonBlankOr(row.displayName(), row.reference()), 256)))
                            .toList();
                }
            }
        } catch (RuntimeException ignored) {
            // Historical metadata is optional: its failure must not break a source-free turn.
            return List.of();
        }
        return List.of();
    }

    private String conversationTurn(String role, String content) {
        String text = content == null ? "" : content.trim();
        if (text.isBlank()) {
            return null;
        }
        String label = role == null || role.isBlank() ? "message" : role.trim().toLowerCase(Locale.ROOT);
        return label + ": " + bounded(text, RECENT_TURN_MAX_CHARS);
    }

    private DomainRow findDomain(
            String sql,
            String ownerKey,
            String diagramId,
            boolean includeCurrentXml
    ) {
        List<DomainRow> rows = jdbc.query(
                sql,
                (resultSet, rowNum) -> mapDomain(resultSet, includeCurrentXml),
                diagramId, ownerKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private DomainRow mapDomain(ResultSet resultSet, boolean includeCurrentXml) throws SQLException {
        return new DomainRow(
                resultSet.getString("diagram_id"),
                resultSet.getString("user_id"),
                resultSet.getString("diagram_chartbook_id"),
                instant(resultSet.getTimestamp("diagram_updated_at")),
                nullableLong(resultSet, "canvas_version"),
                includeCurrentXml ? resultSet.getString("current_xml") : null,
                resultSet.getString("canvas_content_hash"),
                resultSet.getString("canvas_summary"),
                resultSet.getString("canvas_analysis_json"),
                resultSet.getString("active_chartbook_id"),
                resultSet.getString("chartbook_owner_key"),
                resultSet.getString("chartbook_status"),
                instant(resultSet.getTimestamp("chartbook_updated_at")),
                nullableLong(resultSet, "chartbook_profile_version"),
                resultSet.getString("chartbook_profile_instructions"),
                resultSet.getString("chartbook_profile_goal"),
                resultSet.getString("chartbook_profile_summary"),
                resultSet.getString("chartbook_profile_glossary_json"),
                resultSet.getString("chartbook_profile_default_style_json"),
                resultSet.getString("chartbook_profile_stable_constraints_json"),
                resultSet.getString("chartbook_profile_state"),
                nullableLong(resultSet, "auto_memory_version"),
                canonicalMemoryJson(resultSet.getString("auto_memory_json")));
    }

    private static String canonicalMemoryJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "[]";
        }
        List<JSONObject> values = JSON.parseArray(rawJson, JSONObject.class);
        // JSON_ARRAYAGG avoids GROUP_CONCAT truncation. Canonical ordering makes the Context
        // digest stable even though MySQL does not define aggregate element order.
        values.sort(java.util.Comparator
                .comparingInt((JSONObject value) ->
                        "CHARTBOOK".equals(value.getString("scopeType")) ? 0 : 1)
                .thenComparing(
                        value -> nonBlankOr(value.getString("semanticKey"), ""),
                        String.CASE_INSENSITIVE_ORDER));
        return JSON.toJSONString(values);
    }

    private ExecutionCheck checkExecution(FencedAttempt attempt) {
        try {
            List<ExecutionRow> rows = jdbc.query(
                    SELECT_EXECUTION,
                    (resultSet, rowNum) -> mapExecution(resultSet),
                    attempt.key().ownerKey(), attempt.key().canonicalConversationId(), attempt.key().turnId());
            if (rows.isEmpty()) {
                return new ExecutionCheck.Unavailable();
            }
            ExecutionRow row = rows.get(0);
            if (!row.currentFor(attempt)) {
                return new ExecutionCheck.FenceLost();
            }
            if (row.contextMessageHighWater() != attempt.contextMessageHighWater()
                    || !attempt.inputBindingDigest().equals(row.turnInputBindingDigest())) {
                return new ExecutionCheck.Stale();
            }
            return new ExecutionCheck.Valid(row);
        } catch (RuntimeException exception) {
            return new ExecutionCheck.Unavailable();
        }
    }

    private ExecutionRow mapExecution(ResultSet resultSet) throws SQLException {
        return new ExecutionRow(
                resultSet.getString("current_attempt_id"),
                resultSet.getLong("attempt_epoch"),
                resultSet.getString("diagram_id"),
                nullableLong(resultSet, "request_message_id"),
                resultSet.getString("turn_input_binding_digest"),
                resultSet.getString("attachment_binding_digest"),
                resultSet.getLong("context_message_high_water"),
                instant(resultSet.getTimestamp("lease_expires_at")),
                instant(resultSet.getTimestamp("database_now")),
                TurnStatus.valueOf(resultSet.getString("status")));
    }

    private ContextCandidateLoadOutcome candidateExecutionOutcome(ExecutionCheck check, TurnKey key) {
        if (check instanceof ExecutionCheck.FenceLost) {
            return new ContextCandidateLoadOutcome.FenceLost(new TurnStatusRef(key));
        }
        if (check instanceof ExecutionCheck.Stale) {
            return unavailableCandidate(key, TurnFailureCode.STALE_ATTEMPT);
        }
        return unavailableCandidate(key, TurnFailureCode.TERMINAL_UNAVAILABLE);
    }

    private ContextMaterializationOutcome materializationExecutionOutcome(ExecutionCheck check, TurnKey key) {
        if (check instanceof ExecutionCheck.FenceLost) {
            return new ContextMaterializationOutcome.FenceLost(new TurnStatusRef(key));
        }
        if (check instanceof ExecutionCheck.Stale) {
            return unavailableMaterialization(new TurnStatusRef(key), TurnFailureCode.STALE_ATTEMPT);
        }
        return unavailableMaterialization(new TurnStatusRef(key), TurnFailureCode.TERMINAL_UNAVAILABLE);
    }

    private ContextCandidateLoadOutcome.Unavailable unavailableCandidate(TurnKey key, TurnFailureCode code) {
        return new ContextCandidateLoadOutcome.Unavailable(new TurnStatusRef(key), code, RETRY_AFTER);
    }

    private ContextMaterializationOutcome.Unavailable unavailableMaterialization(
            FencedAttempt attempt,
            TurnFailureCode code
    ) {
        return unavailableMaterialization(new TurnStatusRef(attempt.key()), code);
    }

    private ContextMaterializationOutcome.Unavailable unavailableMaterialization(
            TurnStatusRef status,
            TurnFailureCode code
    ) {
        return new ContextMaterializationOutcome.Unavailable(status, code, RETRY_AFTER);
    }

    private String requiredBindingDigest(ExecutionRow execution) {
        if (execution.attachmentBindingDigest() == null || execution.attachmentBindingDigest().isBlank()) {
            throw new IllegalStateException("ATTACHMENT_BINDING_DIGEST_MISSING");
        }
        return execution.attachmentBindingDigest();
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String bounded(String value, int limit) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private static String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record ExecutionRow(
            String attemptId,
            long attemptEpoch,
            String diagramId,
            Long requestMessageId,
            String turnInputBindingDigest,
            String attachmentBindingDigest,
            long contextMessageHighWater,
            Instant leaseExpiresAt,
            Instant databaseNow,
            TurnStatus status
    ) {
        boolean currentFor(FencedAttempt attempt) {
            return status == TurnStatus.RUNNING
                    && Objects.equals(attemptId, attempt.attemptId())
                    && attemptEpoch == attempt.attemptEpoch()
                    && leaseExpiresAt != null
                    && databaseNow != null
                    && leaseExpiresAt.isAfter(databaseNow);
        }
    }

    private record DomainRow(
            String diagramId,
            String ownerKey,
            String diagramChartbookId,
            Instant diagramUpdatedAt,
            Long canvasVersion,
            String currentXml,
            String canvasContentHash,
            String canvasSummary,
            String canvasAnalysisJson,
            String chartbookId,
            String chartbookOwnerKey,
            String chartbookStatus,
            Instant chartbookUpdatedAt,
            Long profileVersion,
            String profileInstructions,
            String profileGoal,
            String profileSummary,
            String profileGlossaryJson,
            String profileDefaultStyleJson,
            String profileStableConstraintsJson,
            String profileState,
            Long memoryVersion,
            String memoryJson
    ) {
        boolean hasCanvas() {
            return canvasVersion != null && canvasVersion >= 0;
        }

        long canvasVersionValue() {
            return canvasVersion == null ? 0 : canvasVersion;
        }

        String canvasDigest() {
            return digest("canvas", diagramId, String.valueOf(canvasVersionValue()),
                    canvasContentHash, canvasSummary, canvasAnalysisJson);
        }

        boolean hasActiveChartbook() {
            return chartbookId != null
                    && chartbookId.equals(diagramChartbookId)
                    && Objects.equals(ownerKey, chartbookOwnerKey)
                    && "ACTIVE".equals(chartbookStatus);
        }

        boolean hasProfile() {
            return hasActiveChartbook() && profileVersion != null && profileVersion >= 0;
        }

        long profileVersionValue() {
            return profileVersion == null ? 0 : profileVersion;
        }

        String profileDigest() {
            return ChartbookProfilePersistenceCodec.digestRaw(
                    chartbookId, profileVersionValue(), profileInstructions, profileGoal, profileSummary,
                    profileGlossaryJson, profileDefaultStyleJson, profileStableConstraintsJson, profileState);
        }

        boolean hasMemory() {
            return memoryVersion != null && memoryVersion > 0
                    && memoryJson != null && !memoryJson.isBlank() && !"[]".equals(memoryJson.trim());
        }

        long memoryVersionValue() {
            return memoryVersion == null ? 0 : memoryVersion;
        }

        String memoryDigest() {
            return digest("memory", chartbookId, String.valueOf(memoryVersionValue()), memoryJson);
        }

        long membershipRevision() {
            return Math.max(epochMillis(diagramUpdatedAt), epochMillis(chartbookUpdatedAt));
        }

        long chartbookStatusRevision() {
            return epochMillis(chartbookUpdatedAt);
        }

        String membershipDigest() {
            return digest("membership", diagramId, ownerKey, diagramChartbookId, chartbookId,
                    chartbookOwnerKey, chartbookStatus, String.valueOf(epochMillis(diagramUpdatedAt)),
                    String.valueOf(epochMillis(chartbookUpdatedAt)));
        }

        private static long epochMillis(Instant value) {
            return value == null ? 0 : value.toEpochMilli();
        }
    }

    private record AttachmentRow(String reference, String displayName, String declaredMime) {
    }

    private record ConversationMessageRow(
            Long id,
            String conversationId,
            String role,
            String content
    ) {
    }

    private record SliceResolution<T>(ContextRead<T> value, boolean retry) {
        static <T> SliceResolution<T> exact(ContextRead<T> value) {
            return new SliceResolution<>(value, false);
        }

        static <T> SliceResolution<T> retrying() {
            return new SliceResolution<>(null, true);
        }
    }

    private sealed interface ExecutionCheck
            permits ExecutionCheck.Valid, ExecutionCheck.FenceLost, ExecutionCheck.Stale,
            ExecutionCheck.Unavailable {
        record Valid(ExecutionRow row) implements ExecutionCheck {
        }

        record FenceLost() implements ExecutionCheck {
        }

        record Stale() implements ExecutionCheck {
        }

        record Unavailable() implements ExecutionCheck {
        }
    }

    private static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                String normalized = value == null ? "" : value;
                byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '|');
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
