package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

/** Read projection for attachments durably bound to one conversation message. */
@Data
public class ConversationMessageAttachmentPO {
    private Long messageId;
    private String fileRef;
    private String displayName;
}
