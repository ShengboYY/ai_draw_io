package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.demand.DemandResolutionPolicy;
import org.zipp.ai.application.turn.demand.AmbiguousSourceDemandProposal;
import org.zipp.ai.application.turn.demand.CurrentInstructionSpan;
import org.zipp.ai.application.turn.demand.NoSourceDemand;
import org.zipp.ai.application.turn.demand.NoSourceDemandProposal;
import org.zipp.ai.application.turn.demand.ProposalEvidence;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandProposal;
import org.zipp.ai.application.turn.demand.SourceDemandResolver;
import org.zipp.ai.application.turn.demand.SourceDemandResolution;
import org.zipp.ai.application.turn.demand.SourceDemandUnavailable;
import org.zipp.ai.application.turn.demand.TypedSourceDemandProposal;
import org.zipp.ai.application.turn.skill.DiagramSkillBinding;
import org.zipp.ai.application.turn.skill.DiagramSkillCatalogSnapshot;
import org.zipp.ai.application.turn.skill.DiagramSkillSelectionSource;
import org.zipp.ai.application.turn.skill.ResolvedDiagramSkillSelection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs one semantic model call and applies deterministic source policy to its untrusted result.
 */
public final class TurnClassificationService {

    private static final List<String> REQUIRED_DRAWING_SKILLS = List.of(
            "drawio-xml-guide",
            "drawio-visual-design");

    private final SemanticIntentRouterPort router;
    private final SourceDemandResolver demandResolver;
    private final DemandResolutionPolicy demandPolicy;

    public TurnClassificationService(
            SemanticIntentRouterPort router,
            SourceDemandResolver demandResolver,
            DemandResolutionPolicy demandPolicy
    ) {
        this.router = Objects.requireNonNull(router, "router");
        this.demandResolver = Objects.requireNonNull(demandResolver, "demandResolver");
        this.demandPolicy = Objects.requireNonNull(demandPolicy, "demandPolicy");
    }

    public TurnClassificationOutcome classify(
            SemanticRouterInput routerInput,
            RestrictedSourceDemandInput demandInput
    ) {
        Objects.requireNonNull(routerInput, "routerInput");
        Objects.requireNonNull(demandInput, "demandInput");
        if (!routerInput.instruction().digest().equals(demandInput.instruction().digest())) {
            return new TurnClassificationUnavailable("CLASSIFICATION_INPUT_DIGEST_MISMATCH");
        }
        if (!routerInput.modelInputBinding().isBound()
                || !routerInput.eligibleAttachmentRefs()
                .equals(demandInput.eligibleAttachmentRefs())
                || !routerInput.attachmentCandidates().equals(demandInput.attachmentCandidates())
                || !routerInput.chartbookMembership().equals(demandInput.chartbookMembership())
                || !routerInput.attachmentBindingDigest()
                .equals(demandInput.attachmentBindingDigest())) {
            return new TurnClassificationUnavailable("CLASSIFICATION_MODEL_INPUT_BINDING_INVALID");
        }

        SemanticIntentOutcome intentOutcome;
        try {
            intentOutcome = router.route(routerInput);
        } catch (RuntimeException exception) {
            return new TurnClassificationUnavailable("V2_SEMANTIC_ROUTER_UNAVAILABLE");
        }
        if (intentOutcome instanceof SemanticIntentUnavailable unavailable) {
            return new TurnClassificationUnavailable(unavailable.code());
        }
        SemanticIntent intent = ((SemanticIntentReady) intentOutcome).intent();
        SourceDemandProposal proposal = proposalFrom(intent.sourceIntent(), demandInput);
        var resolution = demandResolver.resolve(proposal, demandInput, demandPolicy);
        if (resolution instanceof SourceDemandUnavailable unavailable) {
            return new TurnClassificationUnavailable(unavailable.code());
        }
        SkillResolution skillResolution = isPlainDrawing(intent, resolution)
                ? resolveSkills(routerInput, intent)
                : SkillResolution.ready(ResolvedDiagramSkillSelection.empty());
        if (skillResolution.errorCode() != null) {
            return new TurnClassificationUnavailable(skillResolution.errorCode());
        }
        return new TurnClassificationReady(new TurnClassification(
                routerInput.instruction(), intent, proposal, resolution,
                skillResolution.selection()));
    }

    private SkillResolution resolveSkills(
            SemanticRouterInput input,
            SemanticIntent intent
    ) {
        DiagramSkillCatalogSnapshot catalog = input.skillCatalog();
        boolean routerSelected = !"none".equals(intent.skillName());
        boolean userSelected = !input.requestedDiagramSkills().isEmpty();
        if (!catalog.available()) {
            if (routerSelected || userSelected) {
                return SkillResolution.error("V2_SKILL_CATALOG_UNAVAILABLE");
            }
            // Compatibility inputs have no catalog projection and therefore no skill contract.
            return SkillResolution.ready(ResolvedDiagramSkillSelection.empty());
        }

        Map<String, DiagramSkillBinding> selectable = new LinkedHashMap<>();
        catalog.selectableSkills().forEach(skill -> selectable.put(skill.name(), skill));
        List<DiagramSkillBinding> selected = new ArrayList<>();
        DiagramSkillSelectionSource source = DiagramSkillSelectionSource.NONE;
        if (userSelected) {
            source = DiagramSkillSelectionSource.USER;
            for (var request : input.requestedDiagramSkills()) {
                DiagramSkillBinding skill = selectable.get(request.value());
                if (skill == null) {
                    return SkillResolution.error("V2_SKILL_SELECTION_INVALID");
                }
                selected.add(skill);
            }
        } else if (routerSelected) {
            DiagramSkillBinding skill = selectable.get(intent.skillName());
            if (skill == null) {
                return SkillResolution.error("V2_SEMANTIC_SKILL_INVALID");
            }
            source = DiagramSkillSelectionSource.ROUTER;
            selected.add(skill);
        }

        List<DiagramSkillBinding> required = new ArrayList<>();
        Map<String, DiagramSkillBinding> shared = new LinkedHashMap<>();
        catalog.sharedSkills().forEach(skill -> shared.put(skill.name(), skill));
        for (String requiredName : REQUIRED_DRAWING_SKILLS) {
            DiagramSkillBinding skill = shared.get(requiredName);
            if (skill == null) {
                return SkillResolution.error("V2_REQUIRED_SKILL_UNAVAILABLE");
            }
            required.add(skill);
        }
        // Selected skills are also required by the future Plain runtime for this pinned plan.
        required.addAll(selected);
        return SkillResolution.ready(new ResolvedDiagramSkillSelection(
                selected, required, source, catalog.digest()));
    }

    private boolean isPlainDrawing(
            SemanticIntent intent,
            SourceDemandResolution resolution
    ) {
        boolean drawing = intent.action() == SemanticAction.CREATE
                || intent.action() == SemanticAction.EDIT
                || intent.action() == SemanticAction.LAYOUT;
        return drawing
                && resolution instanceof ResolvedSourceDemand resolved
                && resolved.decision() instanceof NoSourceDemand;
    }

    private record SkillResolution(
            ResolvedDiagramSkillSelection selection,
            String errorCode
    ) {
        private static SkillResolution ready(ResolvedDiagramSkillSelection selection) {
            return new SkillResolution(selection, null);
        }

        private static SkillResolution error(String code) {
            return new SkillResolution(null, code);
        }
    }

    private SourceDemandProposal proposalFrom(
            SemanticSourceIntent source,
            RestrictedSourceDemandInput input
    ) {
        var instruction = input.instruction();
        var span = new CurrentInstructionSpan(
                0, instruction.value().length(),
                instruction.spanDigest(0, instruction.value().length()));
        var evidence = new ProposalEvidence(
                List.of(span),
                source.confidence(),
                java.util.Optional.ofNullable(source.relevanceQuery()),
                input.inputDigest(),
                demandPolicy.modelVersion(),
                demandPolicy.policyVersion());
        return switch (source.kind()) {
            case NO_SOURCE -> new NoSourceDemandProposal(evidence, source.safeReason());
            case AMBIGUOUS -> new AmbiguousSourceDemandProposal(evidence, source.safeReason());
            default -> new TypedSourceDemandProposal(
                    source.kind().demandKind(),
                    source.attachmentRefs(),
                    source.relevanceQuery(),
                    evidence,
                    source.safeReason());
        };
    }
}
