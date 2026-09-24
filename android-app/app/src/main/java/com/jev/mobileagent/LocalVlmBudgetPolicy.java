package com.jev.mobileagent;

import java.util.Locale;

/** Price gate for the one model rate reviewed for this mobile acceptance run. */
public final class LocalVlmBudgetPolicy {
    public static final String REVIEWED_PROVIDER = "GUI-Plus";
    public static final String REVIEWED_MODEL = "gui-plus-2026-02-26";
    public static final int INPUT_TOKEN_RESERVATION_ESTIMATE = 8192;
    public static final int OUTPUT_TOKEN_LIMIT = 1024;
    public static final double INPUT_CNY_PER_MILLION = 1.5;
    public static final double OUTPUT_CNY_PER_MILLION = 4.5;

    private LocalVlmBudgetPolicy() {
    }

    public static boolean isReviewedProfile(String provider, String model) {
        return REVIEWED_PROVIDER.toLowerCase(Locale.ROOT).equals(normalize(provider))
                && REVIEWED_MODEL.equals(normalize(model));
    }

    /** This estimate is not a request input limit; actual provider usage is settled after response. */
    public static double requestReservationCny() {
        return estimatedCostCny(INPUT_TOKEN_RESERVATION_ESTIMATE, OUTPUT_TOKEN_LIMIT);
    }

    public static double actualCostCny(Long inputTokens, Long outputTokens) {
        if (inputTokens == null || outputTokens == null || inputTokens < 0L || outputTokens < 0L) {
            throw new IllegalArgumentException("complete nonnegative token usage is required");
        }
        return estimatedCostCny(inputTokens, outputTokens);
    }

    public static boolean exceeds(double accountedCny, double capCny) {
        return accountedCny > capCny + 1e-12;
    }

    private static double estimatedCostCny(long inputTokens, long outputTokens) {
        return (inputTokens * INPUT_CNY_PER_MILLION
                + outputTokens * OUTPUT_CNY_PER_MILLION) / 1_000_000.0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
