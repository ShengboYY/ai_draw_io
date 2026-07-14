package org.zipp.ai.domain.agent.model.valobj.usage;

import org.apache.commons.lang3.StringUtils;

/** Immutable pricing inputs captured with a model call so historical cost does not drift. */
public record LlmPricingSnapshot(String version,
                                 double inputPricePerMillionUsd,
                                 double outputPricePerMillionUsd,
                                 Double estimatedCostUsd) {

    public static final String VERSION = "pricing-2026-07-14-v1";

    public static LlmPricingSnapshot capture(String model, Integer promptTokens, Integer completionTokens) {
        double[] price = pricePerMillion(model);
        Double cost = promptTokens == null || completionTokens == null
                ? null
                : promptTokens * price[0] / 1_000_000D + completionTokens * price[1] / 1_000_000D;
        return new LlmPricingSnapshot(VERSION, price[0], price[1], cost);
    }

    private static double[] pricePerMillion(String model) {
        String normalized = StringUtils.defaultString(model).toLowerCase();
        if (normalized.contains("gpt-5") || normalized.contains("gpt5")) return new double[]{1.25D, 10D};
        if (normalized.contains("gpt-4o") || normalized.contains("4o-mini")) return new double[]{2.5D, 10D};
        if (normalized.contains("opus")) return new double[]{15D, 75D};
        if (normalized.contains("sonnet")) return new double[]{3D, 15D};
        if (normalized.contains("haiku")) return new double[]{0.8D, 4D};
        if (normalized.contains("gemini")) return new double[]{1.25D, 5D};
        return new double[]{2D, 8D};
    }
}
