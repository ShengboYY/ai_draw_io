package org.zipp.ai.application.turn;

/** Decodes the versioned terminal envelope shared by every lifecycle operation. */
public final class TerminalOutcomeDecoder {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String UNAVAILABLE_CODE = "TERMINAL_PAYLOAD_UNAVAILABLE";

    public DecodeResult decode(
            TurnStatus status,
            String terminalCode,
            Integer schemaVersion,
            String terminalPayloadType,
            String terminalPayloadRef,
            String terminalPayloadJson
    ) {
        if (status == null || !status.isTerminal()
                || terminalCode == null || terminalCode.isBlank()
                || terminalPayloadType == null || terminalPayloadType.isBlank()
                || schemaVersion == null || schemaVersion != CURRENT_SCHEMA_VERSION) {
            return new DecodeResult.Unavailable(UNAVAILABLE_CODE);
        }
        return new DecodeResult.Decoded(new PersistedTurnOutcome(
                status,
                terminalCode,
                terminalPayloadType,
                terminalPayloadRef,
                terminalPayloadJson));
    }

    public sealed interface DecodeResult permits DecodeResult.Decoded, DecodeResult.Unavailable {

        record Decoded(PersistedTurnOutcome outcome) implements DecodeResult {
        }

        record Unavailable(String code) implements DecodeResult {

            public Unavailable {
                ContractValues.requiredText(code, "code");
            }
        }
    }
}
