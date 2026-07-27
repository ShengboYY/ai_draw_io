package org.zipp.ai.domain.citation.answer;

import org.zipp.ai.domain.grounding.EvidenceAccessContext;
import org.zipp.ai.domain.retrieval.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Domain service for grounded answers; intentionally has no canvas mutation port. */
public final class EvidenceAnswerService {
    private final EvidenceAnswerGeneratorPort generator;
    private final EvidenceAnswerGuard guard;
    private final EvidenceAnswerCommitPort commits;

    public EvidenceAnswerService(EvidenceAnswerGeneratorPort generator, EvidenceAnswerGuard guard,
                                 EvidenceAnswerCommitPort commits) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.commits = Objects.requireNonNull(commits, "commits");
    }

    public EvidenceAnswerResult answer(EvidenceAnswerCommand command, PreparedEvidence prepared) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(prepared, "prepared");
        EvidenceAccessContext access = EvidenceAccessContext.from(
                prepared.bundle(), command.aiKnowledgeAllowed());
        RunResourceDomain resources = prepared.resources();
        try {
            AnswerProposal proposal = generator.generate(new EvidenceAnswerGeneratorPort.GenerationCommand(
                    command.runId(), command.question(), targetContext(command, prepared),
                    command.conversationContext(), access.items()));
            EvidenceAnswerGuard.Result guarded = guard.validate(proposal, access);
            if (!guarded.accepted()) {
                resources.closeExactlyOnce(CloseReason.FAILED);
                return EvidenceAnswerResult.rejected(command.messageId(), guarded.errors());
            }
            String content = render(guarded);
            List<EvidenceAnswerCommitPort.CitationWrite> citations = writes(command, guarded.claims(), access);
            resources.beginCommit();
            EvidenceAnswerCommitPort.CommitStatus status = commits.commit(new EvidenceAnswerCommitPort.CommitPlan(
                    command.owner(), command.diagramId(), command.sessionId(), command.messageId(),
                    command.requestId(), command.runId(), command.expectedCanvasVersion(),
                    command.expectedCanvasContentHash(), access.runGeneration(), content, citations));
            resources.closeExactlyOnce(CloseReason.COMMITTED);
            return new EvidenceAnswerResult(status == EvidenceAnswerCommitPort.CommitStatus.COMMITTED,
                    command.messageId(), content, guarded.claims(), guarded.gaps(), guarded.conflicts(),
                    usedSources(guarded.claims(), access), List.of());
        } catch (RuntimeException failure) {
            resources.closeExactlyOnce(CloseReason.FAILED);
            return EvidenceAnswerResult.rejected(command.messageId(), List.of("EVIDENCE_ANSWER_FAILED"));
        }
    }

    private String targetContext(EvidenceAnswerCommand command, PreparedEvidence prepared) {
        if (prepared.targets().isEmpty()) return command.targetContext();
        return prepared.targets().stream().map(target -> "cellId=" + target.cellId()
                + ",kind=" + target.kind() + ",label=" + String.valueOf(target.label())
                + ",sourceId=" + target.sourceId() + ",targetId=" + target.targetId()
                + ",nearbyLabels=" + target.nearbyLabels())
                .reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private List<EvidenceAnswerCommitPort.CitationWrite> writes(EvidenceAnswerCommand command,
                                                                 List<AnswerClaim> claims,
                                                                 EvidenceAccessContext access) {
        List<EvidenceAnswerCommitPort.CitationWrite> writes = new ArrayList<>();
        for (AnswerClaim claim : claims) {
            List<EvidenceAnswerCommitPort.EvidenceLink> links = claim.citationKeys().stream()
                    .map(key -> access.item(key).map(item -> new EvidenceAnswerCommitPort.EvidenceLink(
                            key, item.evidenceId(), item.materialId(), item.versionId(), item.revisionId(),
                            "SUPPORT", item.origin().name())).orElseThrow()).toList();
            writes.add(new EvidenceAnswerCommitPort.CitationWrite(
                    opaque("ans", command.runId() + ":" + claim.claimKey()), claim.claimKey(),
                    claim.supportType(), sha256(claim.statementText()), links));
        }
        return List.copyOf(writes);
    }

    private List<EvidenceBundleItem> usedSources(List<AnswerClaim> claims, EvidenceAccessContext access) {
        LinkedHashMap<String, EvidenceBundleItem> used = new LinkedHashMap<>();
        for (AnswerClaim claim : claims) for (String key : claim.citationKeys()) {
            access.item(key).ifPresent(item -> used.putIfAbsent(key, item));
        }
        return List.copyOf(used.values());
    }

    private String render(EvidenceAnswerGuard.Result guarded) {
        List<String> lines = new ArrayList<>();
        for (AnswerClaim claim : guarded.claims()) {
            lines.add("- " + claim.statementText().trim() + " [" + claim.claimKey().trim() + "]");
        }
        if (!guarded.conflicts().isEmpty()) {
            lines.add("");
            lines.add("存在相互冲突的资料，以下部分未合并为事实。 / Conflicting evidence was not merged.");
        }
        if (!guarded.gaps().isEmpty()) {
            lines.add("");
            lines.add("部分问题缺少足够资料。 / Some facets lack sufficient evidence.");
        }
        return String.join("\n", lines);
    }

    private String opaque(String prefix, String value) {
        return prefix + "_" + sha256(value).substring(0, 24);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
