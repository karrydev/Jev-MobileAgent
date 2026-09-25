package com.jev.mobileagent;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Cautious access and reserve gate for the one Jev profile reviewed for this run. */
public final class JevShadowBudgetPolicy {
    public static final String REVIEWED_PROVIDER = "TypeSafe";
    public static final String REVIEWED_MODEL = "jev-1.13.0";
    public static final String REVIEWED_ENDPOINT = "https://api.typesafe.ai/v1/systemone";
    public static final int MAX_TOTAL_CALLS = 70;
    public static final int MAX_CALLS_PER_TASK = LocalTaskStore.MAX_STEPS;
    public static final double RESERVATION_CNY_PER_CALL = 0.01;
    public static final String FX_STATUS = "not_converted";
    public static final String ACTUAL_BILL_STATUS = "unknown";

    private JevShadowBudgetPolicy() {
    }

    public static boolean isReviewedProfile(ModelProfileStore.Profile profile) {
        if (profile == null || !REVIEWED_PROVIDER.toLowerCase(Locale.ROOT)
                .equals(profile.provider.trim().toLowerCase(Locale.ROOT))
                || !REVIEWED_MODEL.equals(profile.model.trim())) {
            return false;
        }
        try {
            URI endpoint = new URI(profile.endpoint.trim());
            URI reviewed = new URI(REVIEWED_ENDPOINT);
            return "https".equalsIgnoreCase(endpoint.getScheme())
                    && reviewed.getHost().equalsIgnoreCase(endpoint.getHost())
                    && reviewed.getRawPath().equals(endpoint.getRawPath())
                    && endpoint.getPort() <= 0
                    && endpoint.getRawUserInfo() == null
                    && endpoint.getRawFragment() == null
                    && endpoint.getRawQuery() == null;
        } catch (URISyntaxException | NullPointerException exception) {
            return false;
        }
    }
}
