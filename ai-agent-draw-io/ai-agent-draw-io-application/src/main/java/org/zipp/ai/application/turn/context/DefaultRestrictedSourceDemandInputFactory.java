package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;

import java.util.List;
import java.util.Set;

/** Creates the demand projection without exposing conversation, Profile, Memory, or source data. */
public final class DefaultRestrictedSourceDemandInputFactory
        implements RestrictedSourceDemandInputFactory {

    @Override
    public RestrictedSourceDemandInput create(BaseTurnContext base) {
        if (base == null) {
            throw new IllegalArgumentException("base context must not be null");
        }
        CurrentMessageAttachmentsContext attachments = value(base.attachments());
        ActiveClarificationContext clarification = value(base.activeClarification());
        ChartbookMembershipContext membership = value(base.membership());
        List<OpaqueConversationFileRef> refs = attachments == null
                ? List.of()
                : attachments.values().stream().map(CurrentMessageAttachmentView::reference).toList();
        Set<String> labels = clarification == null ? Set.of() : clarification.safeOptionLabels();
        java.util.Optional<String> membershipId = membership == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(membership.chartbookId());
        if (attachments == null) {
            return new RestrictedSourceDemandInput(base.request().instruction(), refs, membershipId, labels);
        }
        return new RestrictedSourceDemandInput(
                base.request().instruction(), refs, membershipId, labels, attachments.bindingDigest());
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
