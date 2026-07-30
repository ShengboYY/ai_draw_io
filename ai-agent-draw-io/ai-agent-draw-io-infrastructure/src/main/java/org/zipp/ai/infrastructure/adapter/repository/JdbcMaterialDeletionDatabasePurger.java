package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

/** Fixed-statement content purge; statements are ordered from leaves toward MaterialVersion. */
@Repository
public class JdbcMaterialDeletionDatabasePurger implements MaterialDeletionDatabasePurger {
    private static final List<String> DELETE_SQL = List.of(
            "DELETE p FROM retrieval_chunk_vector_projection p JOIN retrieval_chunk c ON c.id=p.retrieval_chunk_id JOIN material_version v ON v.id=c.version_id WHERE v.material_id=?",
            "DELETE p FROM retrieval_projection_manifest p JOIN material_processing_revision r ON r.id=p.revision_id JOIN material_version v ON v.id=r.version_id WHERE v.material_id=?",
            "DELETE b FROM retrieval_vector_batch b JOIN material_processing_revision r ON r.id=b.revision_id JOIN material_version v ON v.id=r.version_id WHERE v.material_id=?",
            "DELETE p FROM retrieval_revision_vector_projection p JOIN material_processing_revision r ON r.id=p.revision_id JOIN material_version v ON v.id=r.version_id WHERE v.material_id=?",
            "DELETE d FROM retrieval_search_document d WHERE d.material_id=?",
            "DELETE t FROM retrieval_exact_term t JOIN material_version v ON v.id=t.version_id WHERE v.material_id=?",
            "DELETE ce FROM retrieval_chunk_evidence ce JOIN retrieval_chunk c ON c.id=ce.retrieval_chunk_id JOIN material_version v ON v.id=c.version_id WHERE v.material_id=?",
            "DELETE c FROM retrieval_chunk c JOIN material_version v ON v.id=c.version_id WHERE v.material_id=?",
            "DELETE rel FROM evidence_relation rel JOIN evidence_unit e ON e.id=rel.from_evidence_id JOIN material_version v ON v.id=e.version_id WHERE v.material_id=?",
            "DELETE rel FROM evidence_relation rel JOIN evidence_unit e ON e.id=rel.to_evidence_id JOIN material_version v ON v.id=e.version_id WHERE v.material_id=?",
            "DELETE region FROM evidence_region region JOIN evidence_unit e ON e.id=region.evidence_id JOIN material_version v ON v.id=e.version_id WHERE v.material_id=?",
            "DELETE e FROM evidence_unit e JOIN material_version v ON v.id=e.version_id WHERE v.material_id=?",
            "DELETE a FROM material_visual_artifact a JOIN material_processing_revision r ON r.id=a.revision_id JOIN material_version v ON v.id=r.version_id WHERE v.material_id=?",
            "DELETE a FROM material_revision_artifact a JOIN material_processing_revision r ON r.id=a.revision_id JOIN material_version v ON v.id=r.version_id WHERE v.material_id=?",
            "DELETE a FROM material_page_artifact a JOIN material_processing_revision r ON r.id=a.revision_id JOIN material_version v ON v.id=r.version_id WHERE v.material_id=?",
            "DELETE s FROM material_section s JOIN material_processing_revision r ON r.id=s.revision_id JOIN material_version v ON v.id=r.version_id WHERE v.material_id=?",
            "DELETE p FROM material_page p JOIN material_processing_revision r ON r.id=p.revision_id JOIN material_version v ON v.id=r.version_id WHERE v.material_id=?",
            "DELETE l FROM evidence_read_lease l JOIN material_version v ON v.id=l.version_id WHERE v.material_id=?",
            "DELETE j FROM material_processing_job j LEFT JOIN material_processing_revision r ON r.id=j.revision_id LEFT JOIN material_version v ON v.id=r.version_id LEFT JOIN material_upload_session u ON u.id=j.upload_session_id WHERE v.material_id=? OR u.material_id=?",
            "DELETE u FROM material_processing_usage u JOIN material_version owned ON owned.id=u.version_id WHERE owned.material_id=? AND NOT EXISTS (SELECT 1 FROM material_version other WHERE other.content_blob_id=u.content_blob_id AND other.material_id<>?)",
            "DELETE q FROM material_reprocess_request q WHERE q.material_id=?",
            "DELETE pin FROM diagram_source_pin pin WHERE pin.material_id=?",
            "DELETE audit FROM rag_projection_repair_audit audit JOIN material_processing_revision r ON r.id=audit.revision_id JOIN material_version v ON v.id=r.version_id WHERE v.material_id=?",
            "DELETE audit FROM rag_generation_target_repair_audit audit JOIN material_processing_revision r ON r.id=audit.revision_id JOIN material_version v ON v.id=r.version_id WHERE v.material_id=?",
            "DELETE target FROM rag_index_generation_target target JOIN material_processing_revision r ON r.id=target.revision_id JOIN material_version v ON v.id=r.version_id WHERE v.material_id=?",
            "DELETE r FROM material_processing_revision r JOIN material_version v ON v.id=r.version_id WHERE v.material_id=?",
            "DELETE u FROM material_upload_session u WHERE u.material_id=?",
            "DELETE blob FROM material_content_blob blob JOIN material_version owned ON owned.content_blob_id=blob.id WHERE owned.material_id=? AND blob.status='DELETE_PENDING' AND NOT EXISTS (SELECT 1 FROM material_version other WHERE other.content_blob_id=blob.id AND other.material_id<>?)",
            "DELETE v FROM material_version v WHERE v.material_id=?",
            "DELETE l FROM material_scope_link l WHERE l.material_id=?",
            "DELETE t FROM material_tag t WHERE t.material_id=?",
            "DELETE r FROM material_lifecycle_request r WHERE r.material_id=?"
    );

    private final JdbcTemplate jdbc;

    public JdbcMaterialDeletionDatabasePurger(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void purge(String materialId) {
        for (String sql : DELETE_SQL) {
            int placeholders = sql.length() - sql.replace("?", "").length();
            if (placeholders == 0) {
                jdbc.update(sql);
            } else if (placeholders == 2) {
                jdbc.update(sql, materialId, materialId);
            } else {
                jdbc.update(sql, materialId);
            }
        }
    }
}
