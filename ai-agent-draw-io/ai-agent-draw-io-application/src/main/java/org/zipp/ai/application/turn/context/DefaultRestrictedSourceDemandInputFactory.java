package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.demand.AttachmentCandidateOrigin;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;
import org.zipp.ai.application.turn.demand.SourceAttachmentCandidate;

import java.util.List;
import java.util.Set;

/** Creates the demand projection without exposing conversation text, Profile, Memory, or source bodies. */
public final class DefaultRestrictedSourceDemandInputFactory
        implements RestrictedSourceDemandInputFactory {

    @Override
    public RestrictedSourceDemandInput create(BaseTurnContext base) {
        if (base == null) {
            throw new IllegalArgumentException("base context must not be null");
        }
        CurrentMessageAttachmentsContext attachments = value(base.attachments());
        ConversationContext conversation = value(base.conversation());
        ActiveClarificationContext clarification = value(base.activeClarification());
        ChartbookMembershipContext membership = value(base.membership());
        List<SourceAttachmentCandidate> candidates = candidates(attachments, conversation);
        List<OpaqueConversationFileRef> refs = candidates.stream()
                .map(SourceAttachmentCandidate::reference)
                .toList();
        Set<String> labels = clarification == null ? Set.of() : clarification.safeOptionLabels();
        java.util.Optional<String> membershipId = membership == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(membership.chartbookId());
        String bindingDigest = attachments == null
                ? ModelInputBinding.digestOf("attachment-context-absent")
                : attachments.bindingDigest();
        return new RestrictedSourceDemandInput(
                base.request().instruction(),
                refs,
                candidates,
                membershipId,
                labels,
                bindingDigest,
                ModelInputBinding.unbound());
    }

    private List<SourceAttachmentCandidate> candidates(
            CurrentMessageAttachmentsContext current,
            ConversationContext conversation
    ) {
        if (current != null && !current.values().isEmpty()) {
            // Current-message declarations are authoritative and hide every historical candidate.
            return current.values().stream()
                    .map(value -> new SourceAttachmentCandidate(
                            value.reference(),
                            value.mediaType(),
                            value.displayName(),
                            AttachmentCandidateOrigin.CURRENT_MESSAGE))
                    .toList();
        }
        if (conversation == null) {
            return List.of();
        }
        return conversation.recentUserMessageAttachments().stream()
                .map(value -> new SourceAttachmentCandidate(
                        value.reference(),
                        value.mediaType(),
                        value.displayName(),
                        AttachmentCandidateOrigin.RECENT_USER_MESSAGE))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private <T> T value(ContextRead<T> read) {
        if (read instanceof AvailableContext<?> available) {
            return (T) available.value();
        }
        if (read instanceof TruncatedContext<?> truncated) {
            return (T) truncated.value();
        }
        return null;
    }
}
