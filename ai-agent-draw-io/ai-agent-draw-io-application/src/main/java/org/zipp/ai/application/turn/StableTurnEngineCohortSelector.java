package org.zipp.ai.application.turn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/** Deterministic account/org cohort selection; prompt and source data never enter the hash. */
public final class StableTurnEngineCohortSelector implements TurnEngineCohortSelector {

    private final String rolloutSalt;
    private final int rolloutPercent;
    private final Set<String> allowlistedCohorts;

    public StableTurnEngineCohortSelector(
            String rolloutSalt,
            int rolloutPercent,
            Set<String> allowlistedCohorts
    ) {
        this.rolloutSalt = ContractValues.requiredText(rolloutSalt, "rolloutSalt");
        if (rolloutPercent < 0 || rolloutPercent > 100) {
            throw new IllegalArgumentException("rolloutPercent must be between 0 and 100");
        }
        this.rolloutPercent = rolloutPercent;
        this.allowlistedCohorts = Set.copyOf(allowlistedCohorts == null
                ? Set.of() : allowlistedCohorts);
    }

    public static StableTurnEngineCohortSelector legacyOnly() {
        return new StableTurnEngineCohortSelector("m6-default", 0, Set.of());
    }

    @Override
    public SelectedTurnEngine select(String stableCohortKey) {
        String cohort = ContractValues.requiredText(stableCohortKey, "stableCohortKey");
        if (allowlistedCohorts.contains(cohort) || rolloutPercent == 100) {
            return SelectedTurnEngine.V2;
        }
        if (rolloutPercent == 0) {
            return SelectedTurnEngine.LEGACY;
        }
        return bucket(cohort) < rolloutPercent
                ? SelectedTurnEngine.V2
                : SelectedTurnEngine.LEGACY;
    }

    private int bucket(String stableCohortKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((rolloutSalt + "\u0000" + stableCohortKey)
                            .getBytes(StandardCharsets.UTF_8));
            int unsigned = ((digest[0] & 0xff) << 24)
                    | ((digest[1] & 0xff) << 16)
                    | ((digest[2] & 0xff) << 8)
                    | (digest[3] & 0xff);
            return Math.floorMod(unsigned, 100);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
