package org.zipp.ai.domain.retrieval.service;

import org.zipp.ai.domain.retrieval.model.valobj.IndexGenerationState;
import org.zipp.ai.domain.retrieval.model.valobj.RevisionPublicationWork;
import org.zipp.ai.domain.retrieval.projection.VectorProjectionManifest;
import org.zipp.ai.domain.retrieval.projection.RetrievalProjectionManifest;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceManifest;
import org.zipp.ai.domain.ingestion.model.valobj.DocumentStructure;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionGapManifest;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalIndexMode;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Enforces the immutable MySQL/S3/Pinecone boundary before a revision becomes searchable. */
public final class RevisionPublicationGate {

    public void verifyManifest(RevisionPublicationWork work, VectorProjectionManifest manifest) {
        RevisionPublicationWork source = Objects.requireNonNull(work, "work");
        VectorProjectionManifest sealed = Objects.requireNonNull(manifest, "manifest");
        Set<?> manifestEntries = new HashSet<>(sealed.entries());
        if (!source.context().revisionId().equals(sealed.revisionId())
                || !source.context().versionId().equals(sealed.versionId())
                || !source.profile().generationId().equals(sealed.generationId())
                || !source.profile().indexName().equals(sealed.indexName())
                || !source.profile().namespace().equals(sealed.namespace())
                || !source.profile().embeddingModel().equals(sealed.embeddingModel())
                || !source.profile().embeddingModelFingerprint().equals(sealed.embeddingModelFingerprint())
                || source.profile().dimension() != sealed.dimension()
                || !source.profile().metric().equals(sealed.metric())
                || !source.profile().vectorSchemaVersion().equals(sealed.vectorSchemaVersion())
                || !source.profile().tokenizerFingerprint().equals(sealed.tokenizerFingerprint())
                || !source.projectionManifestHash().equals(sealed.manifestHash())
                || source.expectedProjectionCount() != sealed.entries().size()
                || source.indexedProjectionCount() != source.expectedProjectionCount()
                || !manifestEntries.equals(new HashSet<>(source.indexedProjections()))) {
            throw new IllegalArgumentException("publication manifest does not match the authoritative projection");
        }
        if (source.generationState() == IndexGenerationState.BUILDING
                && source.activeGenerationId() != null
                && !source.activeGenerationId().equals(source.profile().generationId())) {
            throw new IllegalStateException("compatibility projection is required before generation switch");
        }
        if (source.generationState() != IndexGenerationState.BUILDING
                && source.generationState() != IndexGenerationState.ACTIVE) {
            throw new IllegalStateException("generation cannot accept published revisions");
        }
    }

    public void verifySourceChain(RevisionPublicationWork work,
                                  RetrievalProjectionManifest retrieval,
                                  EvidenceManifest evidence,
                                  DocumentStructure structure) {
        RevisionPublicationWork source = Objects.requireNonNull(work, "work");
        RetrievalProjectionManifest retrievalSeal = Objects.requireNonNull(retrieval, "retrieval");
        EvidenceManifest evidenceSeal = Objects.requireNonNull(evidence, "evidence");
        DocumentStructure structureSeal = Objects.requireNonNull(structure, "structure");
        long denseEligible = retrievalSeal.chunks().stream()
                .filter(chunk -> chunk.indexMode() == RetrievalIndexMode.DENSE_AND_LEXICAL).count();
        long exactTerms = retrievalSeal.lexicalProjections().stream()
                .mapToLong(projection -> projection.exactTerms().size()).sum();
        long evidenceMappings = retrievalSeal.chunks().stream()
                .mapToLong(chunk -> chunk.evidenceMappings().size()).sum();
        Set<String> evidenceIds = evidenceSeal.units().stream()
                .map(unit -> unit.evidenceId()).collect(java.util.stream.Collectors.toSet());
        Set<String> sectionIds = structureSeal.sections().stream()
                .map(section -> section.sectionId()).collect(java.util.stream.Collectors.toSet());
        boolean unknownEvidence = retrievalSeal.chunks().stream()
                .flatMap(chunk -> chunk.evidenceMappings().stream())
                .anyMatch(mapping -> !evidenceIds.contains(mapping.evidenceId()));
        boolean unknownSection = evidenceSeal.units().stream()
                .anyMatch(unit -> unit.sectionId() != null && !sectionIds.contains(unit.sectionId()));
        if (!source.context().revisionId().equals(retrievalSeal.revisionId())
                || !source.context().versionId().equals(retrievalSeal.versionId())
                || !source.context().revisionId().equals(evidenceSeal.revisionId())
                || !source.context().versionId().equals(evidenceSeal.versionId())
                || !retrievalSeal.evidenceHash().equals(evidenceSeal.evidenceHash())
                || !evidenceSeal.structureHash().equals(structureSeal.structureHash())
                || source.retrievalChunkCount() != retrievalSeal.chunks().size()
                || source.lexicalProjectionCount() != retrievalSeal.lexicalProjections().size()
                || source.exactTermCount() != exactTerms
                || source.chunkEvidenceMappingCount() != evidenceMappings
                || source.evidenceUnitCount() != evidenceSeal.units().size()
                || source.expectedProjectionCount() != denseEligible
                || unknownEvidence || unknownSection) {
            throw new IllegalArgumentException("publication source manifest chain is inconsistent");
        }
    }

    public boolean allVectorsVisible(RevisionPublicationWork work, Set<String> existingVectorIds) {
        return new HashSet<>(work.vectorIds()).equals(Set.copyOf(existingVectorIds));
    }

    public void verifyGapManifest(RevisionPublicationWork work, RevisionGapManifest gapManifest) {
        RevisionGapManifest gaps = Objects.requireNonNull(gapManifest, "gapManifest");
        if (work.gapManifestArtifact() == null
                || !work.context().revisionId().equals(gaps.revisionId())
                || !work.context().versionId().equals(gaps.versionId())) {
            throw new IllegalArgumentException("gap manifest does not belong to the published revision");
        }
    }
}
