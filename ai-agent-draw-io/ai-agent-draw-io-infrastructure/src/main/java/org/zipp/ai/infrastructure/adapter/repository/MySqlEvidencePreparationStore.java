package org.zipp.ai.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.planning.SourcePlanIdentity;
import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import org.zipp.ai.domain.retrieval.EvidenceOrigin;
import org.zipp.ai.domain.retrieval.EvidenceSupportRole;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Durable display-evidence hand-off for grounded and evidence-answer generation. */
@Repository
public class MySqlEvidencePreparationStore {

    private static final String INSERT = """
            INSERT INTO turn_source_evidence_preparation (
                owner_key, conversation_id, turn_id, prepared_ref, plan_fingerprint,
                source_snapshot_ref, manifest_digest, evidence_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                evidence_json = IF(plan_fingerprint = VALUES(plan_fingerprint),
                    VALUES(evidence_json), evidence_json),
                manifest_digest = IF(plan_fingerprint = VALUES(plan_fingerprint),
                    VALUES(manifest_digest), manifest_digest)
            """;
    private static final String SELECT = """
            SELECT prepared_ref, plan_fingerprint, source_snapshot_ref,
                   manifest_digest, evidence_json
            FROM turn_source_evidence_preparation
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ? AND prepared_ref = ?
            """;

    private final JdbcOperations jdbc;

    public MySqlEvidencePreparationStore(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional
    public void save(FencedAttempt attempt, String preparedRef, SourcePlanIdentity plan,
                     String sourceSnapshotRef, String manifestDigest, List<EvidenceBundleItem> items) {
        jdbc.update(INSERT, attempt.key().ownerKey(), attempt.key().canonicalConversationId(),
                attempt.key().turnId(), required(preparedRef), plan.planFingerprint(),
                required(sourceSnapshotRef), required(manifestDigest), JSON.toJSONString(items));
    }

    public Optional<Prepared> find(FencedAttempt attempt, String preparedRef) {
        List<Prepared> rows = jdbc.query(SELECT, (rs, rowNum) -> new Prepared(
                        rs.getString("prepared_ref"), rs.getString("plan_fingerprint"),
                        rs.getString("source_snapshot_ref"), rs.getString("manifest_digest"),
                        decode(rs.getString("evidence_json"))),
                attempt.key().ownerKey(), attempt.key().canonicalConversationId(),
                attempt.key().turnId(), required(preparedRef));
        return rows.stream().findFirst();
    }

    private List<EvidenceBundleItem> decode(String json) {
        JSONArray values = JSON.parseArray(json);
        if (values == null || values.isEmpty()) throw new IllegalStateException("evidence payload is empty");
        return values.stream().map(value -> {
            JSONObject item = (JSONObject) value;
            return new EvidenceBundleItem(
                    item.getString("citationKey"), item.getString("evidenceId"),
                    item.getString("materialId"), item.getString("versionId"),
                    item.getString("revisionId"), item.getString("sourceLabel"),
                    item.getIntValue("pageNumber"), item.getString("modality"),
                    item.getString("text"),
                    EvidenceSupportRole.valueOf(item.getString("supportRole")),
                    EvidenceOrigin.valueOf(item.getString("origin")));
        }).toList();
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("value is required");
        return value.trim();
    }

    public record Prepared(String preparedRef, String planFingerprint, String sourceSnapshotRef,
                           String manifestDigest, List<EvidenceBundleItem> items) {
        public Prepared {
            items = List.copyOf(items);
        }
    }
}
