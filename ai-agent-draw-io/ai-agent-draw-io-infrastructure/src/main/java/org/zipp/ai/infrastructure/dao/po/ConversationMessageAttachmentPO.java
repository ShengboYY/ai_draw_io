package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

/** Read projection for attachments durably bound to one V2 turn. */
@Data
public class ConversationMessageAttachmentPO {
    private String turnId;
    private String fileRef;
    private String displayName;
}
