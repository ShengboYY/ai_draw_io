package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.ClarificationId;
import org.zipp.ai.application.turn.MatchedInstructionSpan;
import org.zipp.ai.application.turn.MemoryWriteRuleVersion;
import org.zipp.ai.application.turn.MemoryWriteSemanticDigest;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.RememberDecisionDeclaration;
import org.zipp.ai.application.turn.ReplyToClarification;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.UntrustedLegacyVersionDeclaration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TurnInputBindingJsonCodecTest {

    @Test
    void roundTripsEveryPinnedDeclarationNeededAfterTakeover() {
        TurnDeclarations declarations = new TurnDeclarations(
                List.of(new OpaqueConversationFileRef("file-1"), new OpaqueConversationFileRef("file-2")),
                new ReplyToClarification(new ClarificationId("clarification-1")),
                List.of(new UntrustedLegacyVersionDeclaration("legacy-source-1")),
                new RememberDecisionDeclaration(
                        2,
                        new MemoryWriteRuleVersion("memory-locale-rule-v2"),
                        new MatchedInstructionSpan("remember this decision"),
                        new MemoryWriteSemanticDigest("sha256:memory"),
                        "chartbook-1",
                        "remembered-decision",
                        "all",
                        "use event naming",
                        "en"));

        TurnInputBindingJsonCodec codec = new TurnInputBindingJsonCodec();

        assertEquals(declarations, codec.decode(codec.encode(declarations)));
    }
}
