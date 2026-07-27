package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.planning.BoundSourcePlan;
import org.zipp.ai.application.turn.planning.DirectCandidateOrigin;

import java.util.Objects;
import java.util.Optional;

/**
 * Capabilities produced after source probing and snapshot preparation. Candidate references are
 * deliberately not accepted here: handlers receive only prepared, owner-fenced capabilities.
 */
public sealed interface SourceAwarePreparedExecution
        permits SourceAwarePreparedExecution.Direct,
        SourceAwarePreparedExecution.Grounded,
        SourceAwarePreparedExecution.EvidenceAnswer {

    record Direct(
            BoundSourcePlan plan,
            SourceCommitBinding sourceBinding,
            String artifactLeaseRef,
            String sourceIdentityRef,
            DirectCandidateOrigin origin
    ) implements SourceAwarePreparedExecution {
        public Direct {
            common(plan, sourceBinding);
            required(artifactLeaseRef, "artifactLeaseRef");
            required(sourceIdentityRef, "sourceIdentityRef");
            Objects.requireNonNull(origin, "origin");
        }
    }

    record Grounded(
            BoundSourcePlan plan,
            SourceCommitBinding sourceBinding,
            String preparedEvidenceRef,
            ValidatedCitationManifest citations,
            Optional<DirectVisualProvenance> directProvenance
    ) implements SourceAwarePreparedExecution {
        public Grounded {
            common(plan, sourceBinding);
            required(preparedEvidenceRef, "preparedEvidenceRef");
            Objects.requireNonNull(citations, "citations");
            directProvenance = directProvenance == null ? Optional.empty() : directProvenance;
        }
    }

    record EvidenceAnswer(
            SourceCommitBinding sourceBinding,
            String preparedEvidenceRef,
            ValidatedCitationManifest citations
    ) implements SourceAwarePreparedExecution {
        public EvidenceAnswer {
            Objects.requireNonNull(sourceBinding, "sourceBinding");
            required(preparedEvidenceRef, "preparedEvidenceRef");
            Objects.requireNonNull(citations, "citations");
        }
    }

    private static void common(BoundSourcePlan plan, SourceCommitBinding sourceBinding) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(sourceBinding, "sourceBinding");
        if (!plan.identity().equals(sourceBinding.planIdentity())) {
            throw new IllegalArgumentException("SOURCE_PLAN_IDENTITY_MISMATCH");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
