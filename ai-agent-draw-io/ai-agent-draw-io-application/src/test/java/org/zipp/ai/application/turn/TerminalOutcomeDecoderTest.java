package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TerminalOutcomeDecoderTest {

    private final TerminalOutcomeDecoder decoder = new TerminalOutcomeDecoder();

    @Test
    void decodesTheCurrentTerminalSchemaIntoAReplayableOutcome() {
        TerminalOutcomeDecoder.DecodeResult.Decoded decoded = assertInstanceOf(
                TerminalOutcomeDecoder.DecodeResult.Decoded.class,
                decoder.decode(TurnStatus.COMPLETED, "COMPLETED", 1, "plain", "payload-1", "{}"));

        assertEquals(TurnStatus.COMPLETED, decoded.outcome().status());
        assertEquals("COMPLETED", decoded.outcome().terminalCode());
    }

    @Test
    void unsupportedOrIncompleteTerminalSchemaIsTypedUnavailable() {
        TerminalOutcomeDecoder.DecodeResult.Unavailable unavailable = assertInstanceOf(
                TerminalOutcomeDecoder.DecodeResult.Unavailable.class,
                decoder.decode(TurnStatus.COMPLETED, "COMPLETED", 2, "plain", "payload-1", "{}"));

        assertEquals(TerminalOutcomeDecoder.UNAVAILABLE_CODE, unavailable.code());
    }
}
