package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds a small, closed action set from one current observation and known parameters. */
public final class JevCandidateBuilder {
    public static final int MAX_CANDIDATES = 32;
    public static final int MAX_INSTRUCTION_CHARS = 2_000;
    public static final int MAX_KNOWN_TEXT_CHARS = 500;
    public static final int MAX_OBSERVATION_BYTES = 256 * 1024;
    private static final long MAX_OBSERVATION_AGE_MILLIS = 60_000L;
    private static final long FUTURE_CLOCK_SKEW_MILLIS = 5_000L;

    private JevCandidateBuilder() {
    }

    public static CandidateSet build(JSONObject observation, JSONObject knownParameters)
            throws JSONException {
        return build(observation, knownParameters, System.currentTimeMillis());
    }

    static CandidateSet build(JSONObject observation, JSONObject knownParameters, long nowMillis)
            throws JSONException {
        if (observation == null) {
            return CandidateSet.fallback("observation_missing", "", 0L);
        }
        if (observation.toString().getBytes(StandardCharsets.UTF_8).length > MAX_OBSERVATION_BYTES) {
            return CandidateSet.fallback("observation_too_large",
                    observation.optString("observation_id", ""),
                    observation.optLong("observation_version", observation.optLong("version", 0L)));
        }
        JSONObject snapshot = new JSONObject(observation.toString());
        JSONObject parameters = knownParameters == null
                ? new JSONObject() : new JSONObject(knownParameters.toString());
        String observationId = snapshot.optString("observation_id", "");
        long version = snapshot.optLong("observation_version", snapshot.optLong("version", 0L));
        String invalid = observationValidity(snapshot, nowMillis);
        if (invalid != null) {
            return CandidateSet.fallback(invalid, observationId, version);
        }

        JSONArray nodes = snapshot.optJSONArray("nodes");
        if (nodes == null) {
            return CandidateSet.fallback("observation_nodes_missing", observationId, version);
        }
        List<Candidate> candidates = new ArrayList<>();
        List<JSONObject> warnings = new ArrayList<>();
        List<String> missingParameters = new ArrayList<>();
        Set<String> candidateIds = new HashSet<>();
        for (int index = 0; index < nodes.length() && candidates.size() < MAX_CANDIDATES; index++) {
            JSONObject node = nodes.optJSONObject(index);
            if (node == null || !node.optBoolean("enabled", true)
                    || !node.optBoolean("visible_to_user", true)) {
                continue;
            }
            String nodeId = node.optString("node_id", "");
            if (!nodeId.matches("[A-Za-z0-9_.:-]{1,160}")) {
                warnings.add(warning("", "invalid_node_id"));
                continue;
            }
            List<ActionSpec> specs = actionSpecs(node);
            for (ActionSpec spec : specs) {
                if (candidates.size() >= MAX_CANDIDATES) {
                    break;
                }
                if (!isSupported(spec.kind)) {
                    warnings.add(warning(nodeId, "unsupported_action_kind"));
                    continue;
                }
                JSONObject actionParameters = parametersFor(spec, node, parameters);
                if (actionParameters == null) {
                    if (spec.missingParameter != null) {
                        missingParameters.add(spec.missingParameter);
                        JSONObject item = warning(nodeId, "missing_required_parameter");
                        item.put("parameter", spec.missingParameter);
                        warnings.add(item);
                    }
                    continue;
                }
                String baseId = candidateId(spec.kind, nodeId, actionParameters);
                String id = baseId;
                int suffix = 2;
                while (!candidateIds.add(id)) {
                    id = baseId + "-" + suffix++;
                }
                String label = nodeLabel(node);
                String description = actionDescription(spec.kind, label, actionParameters,
                        node.optString("role", node.optString("class_name", "")));
                String group = spec.equivalenceGroup;
                if (group == null || group.trim().isEmpty()) {
                    group = node.optString("equivalence_group", "node:" + nodeId);
                }
                candidates.add(new Candidate(id, spec.kind, nodeId, actionParameters,
                        description, group, node));
            }
        }

        if (candidates.isEmpty()) {
            String reason = !missingParameters.isEmpty()
                    ? "missing_required_parameter" : "empty_candidates";
            return CandidateSet.empty(reason, observationId, version, missingParameters, warnings);
        }
        return new CandidateSet(observationId, version, candidates, null,
                missingParameters, warnings);
    }

    private static String observationValidity(JSONObject observation, long nowMillis) {
        String state = observation.optString("page_state", "observed");
        if (!"observed".equalsIgnoreCase(state) && !"available".equalsIgnoreCase(
                observation.optString("availability", ""))) {
            return "observation_unavailable";
        }
        String observationId = observation.optString("observation_id", "");
        long version = observation.optLong("observation_version", observation.optLong("version", 0L));
        if (observationId.isEmpty() || version < 1L) {
            return "observation_identity_missing";
        }
        Long captured = timeMillis(observation.opt("captured_at"));
        Long expires = timeMillis(observation.opt("expires_at"));
        if (captured == null) {
            return "observation_clock_unavailable";
        }
        long now = normalizeNowMillis(nowMillis);
        if (expires != null && now > expires) {
            return "observation_expired";
        }
        if (now < captured - FUTURE_CLOCK_SKEW_MILLIS) {
            return "observation_from_future";
        }
        if (expires == null && now - captured > MAX_OBSERVATION_AGE_MILLIS) {
            return "observation_expired";
        }
        return null;
    }

    private static long normalizeNowMillis(long value) {
        return value < 100_000_000_000L ? value * 1_000L : value;
    }

    private static Long timeMillis(Object value) {
        if (value instanceof Number) {
            double numeric = ((Number) value).doubleValue();
            if (!Double.isFinite(numeric) || numeric < 0.0) {
                return null;
            }
            long integer = ((Number) value).longValue();
            return integer < 100_000_000_000L ? integer * 1_000L : integer;
        }
        if (value instanceof String) {
            try {
                return Instant.parse((String) value).toEpochMilli();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<ActionSpec> actionSpecs(JSONObject node) {
        List<ActionSpec> result = new ArrayList<>();
        JSONArray explicit = node.optJSONArray("actions");
        if (explicit != null) {
            for (int i = 0; i < explicit.length(); i++) {
                Object raw = explicit.opt(i);
                if (raw instanceof String) {
                    result.add(new ActionSpec((String) raw, null, null, null));
                } else if (raw instanceof JSONObject) {
                    JSONObject spec = (JSONObject) raw;
                    Object value = spec.has("duration_ms") ? spec.opt("duration_ms") : spec.opt("direction");
                    result.add(new ActionSpec(spec.optString("kind", ""),
                            spec.optString("parameter", null), value,
                            spec.optString("equivalence_group", null)));
                }
            }
            return result;
        }
        String role = node.optString("role", "").toLowerCase(Locale.ROOT);
        String className = node.optString("class_name", "").toLowerCase(Locale.ROOT);
        if (node.optBoolean("clickable", false)
                || role.equals("button") || role.equals("checkbox") || role.equals("switch")
                || role.equals("link") || role.equals("icon_button")
                || className.endsWith("button") || className.contains("imagebutton")) {
            result.add(new ActionSpec("tap", null, null, null));
        }
        if (node.optBoolean("editable", false)
                || role.equals("text_field") || role.equals("edit_text") || role.equals("search_field")) {
            result.add(new ActionSpec("input_text", node.optString("parameter", "text"), null, null));
        }
        if (node.optBoolean("scrollable", false)) {
            result.add(new ActionSpec("scroll", node.optString("parameter", "direction"), null, null));
        }
        if (node.optBoolean("long_clickable", false)) {
            result.add(new ActionSpec("long_press", node.optString("parameter", "duration_ms"), null, null));
        }
        return result;
    }

    private static JSONObject parametersFor(ActionSpec spec, JSONObject node, JSONObject known)
            throws JSONException {
        JSONObject result = new JSONObject();
        if ("tap".equals(spec.kind) || "back".equals(spec.kind)) {
            return result;
        }
        String parameter = spec.parameter;
        if ("input_text".equals(spec.kind)) {
            if (parameter == null || parameter.isEmpty()) {
                parameter = "text";
            }
            Object value = known.opt(parameter);
            if (!(value instanceof String) || ((String) value).isEmpty()
                    || ((String) value).length() > MAX_KNOWN_TEXT_CHARS) {
                spec.missingParameter = parameter;
                return null;
            }
            return result.put("text", value);
        }
        if ("long_press".equals(spec.kind)) {
            Object value = spec.value != null && spec.value != JSONObject.NULL
                    ? spec.value : known.opt(parameter == null ? "duration_ms" : parameter);
            if (!(value instanceof Number) || ((Number) value).longValue() < 1L
                    || ((Number) value).longValue() > 5_000L) {
                spec.missingParameter = parameter == null ? "duration_ms" : parameter;
                return null;
            }
            return result.put("duration_ms", ((Number) value).longValue());
        }
        if ("scroll".equals(spec.kind)) {
            Object value = spec.value != null && spec.value != JSONObject.NULL
                    ? spec.value : known.opt(parameter == null ? "direction" : parameter);
            if (!(value instanceof String) || !isDirection((String) value)) {
                spec.missingParameter = parameter == null ? "direction" : parameter;
                return null;
            }
            return result.put("direction", value);
        }
        return null;
    }

    private static boolean isDirection(String value) {
        return "up".equals(value) || "down".equals(value) || "left".equals(value) || "right".equals(value);
    }

    private static boolean isSupported(String kind) {
        return "tap".equals(kind) || "input_text".equals(kind) || "long_press".equals(kind)
                || "scroll".equals(kind) || "back".equals(kind);
    }

    private static String nodeLabel(JSONObject node) {
        String[] candidates = {"label", "content_description", "text", "view_id_resource_name", "class_name"};
        for (String key : candidates) {
            String value = node.optString(key, "").trim();
            if (!value.isEmpty()) {
                return truncate(value, 100);
            }
        }
        return "未标记控件";
    }

    private static String actionDescription(String kind, String label, JSONObject parameters, String role) {
        boolean chinese = containsHan(label);
        if ("input_text".equals(kind)) {
            String text = parameters.optString("text", "");
            return chinese ? "在“" + label + "”中输入“" + text + "”"
                    : "Type “" + text + "” into " + label;
        }
        if ("tap".equals(kind)) {
            return chinese ? "点击“" + label + "”" : "Tap " + label;
        }
        if ("long_press".equals(kind)) {
            return chinese ? "长按“" + label + "”" : "Long press " + label;
        }
        if ("scroll".equals(kind)) {
            String direction = parameters.optString("direction", "");
            return chinese ? "在“" + label + "”中向" + direction + "滚动" : "Scroll " + direction + " in " + label;
        }
        return chinese ? "系统返回上一页" : "Press system Back";
    }

    private static boolean containsHan(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.UnicodeScript.of(value.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private static String candidateId(String kind, String nodeId, JSONObject parameters) throws JSONException {
        List<String> keys = new ArrayList<>();
        java.util.Iterator<String> iterator = parameters.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        Collections.sort(keys);
        StringBuilder canonical = new StringBuilder("{\"kind\":")
                .append(JSONObject.quote(kind)).append(",\"parameters\":{");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) canonical.append(',');
            String key = keys.get(i);
            Object value = parameters.get(key);
            canonical.append(JSONObject.quote(key)).append(':');
            if (value instanceof String) canonical.append(JSONObject.quote((String) value));
            else if (value instanceof Number || value instanceof Boolean) canonical.append(value);
            else throw new JSONException("candidate parameter type invalid");
        }
        canonical.append("},\"target_node_id\":").append(JSONObject.quote(nodeId)).append('}');
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < 6; i++) suffix.append(String.format(Locale.ROOT, "%02x", digest[i]));
        return "candidate-" + kind + "-" + nodeId + "-" + suffix;
    }

    private static JSONObject warning(String nodeId, String reason) throws JSONException {
        return new JSONObject().put("node_id", nodeId).put("reason", reason);
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public static final class Candidate {
        public final String id;
        public final String kind;
        public final String nodeId;
        public final String description;
        public final String equivalenceGroup;
        public final JSONObject parameters;
        private final JSONObject sourceNode;

        private Candidate(String id, String kind, String nodeId, JSONObject parameters,
                String description, String equivalenceGroup, JSONObject sourceNode) throws JSONException {
            this.id = id;
            this.kind = kind;
            this.nodeId = nodeId;
            this.parameters = new JSONObject(parameters.toString());
            this.description = truncate(description, MAX_KNOWN_TEXT_CHARS + 160);
            this.equivalenceGroup = equivalenceGroup;
            this.sourceNode = sourceNode == null ? null : new JSONObject(sourceNode.toString());
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("candidate_id", id).put("kind", kind)
                    .put("target_node_id", nodeId).put("parameters", new JSONObject(parameters.toString()))
                    .put("description", description).put("equivalence_group", equivalenceGroup);
        }

        JSONObject sourceNodeSnapshot() throws JSONException {
            return sourceNode == null ? null : new JSONObject(sourceNode.toString());
        }

        private boolean containsPoint(double x, double y) {
            JSONObject bounds = sourceNode == null ? null : sourceNode.optJSONObject("bounds");
            if (bounds == null) return false;
            double left = bounds.optDouble("left", Double.NaN);
            double top = bounds.optDouble("top", Double.NaN);
            double right = bounds.optDouble("right", Double.NaN);
            double bottom = bounds.optDouble("bottom", Double.NaN);
            return Double.isFinite(left) && Double.isFinite(top) && Double.isFinite(right)
                    && Double.isFinite(bottom) && x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    public static final class CandidateSet {
        public final String observationId;
        public final long observationVersion;
        public final List<Candidate> candidates;
        public final String fallbackReason;
        public final List<String> missingParameters;
        public final List<JSONObject> warnings;

        private CandidateSet(String observationId, long observationVersion, List<Candidate> candidates,
                String fallbackReason, List<String> missingParameters, List<JSONObject> warnings) {
            this.observationId = observationId;
            this.observationVersion = observationVersion;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.fallbackReason = fallbackReason;
            this.missingParameters = Collections.unmodifiableList(new ArrayList<>(missingParameters));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        private static CandidateSet fallback(String reason, String id, long version) {
            return new CandidateSet(id, version, Collections.emptyList(), reason,
                    Collections.emptyList(), Collections.emptyList());
        }

        private static CandidateSet empty(String reason, String id, long version,
                List<String> missing, List<JSONObject> warnings) {
            return new CandidateSet(id, version, Collections.emptyList(), reason, missing, warnings);
        }

        public JSONArray criteria() throws JSONException {
            JSONArray result = new JSONArray();
            for (Candidate candidate : candidates) {
                result.put(new JSONObject().put("candidate_id", candidate.id)
                        .put("description", candidate.description));
            }
            return result;
        }

        public JSONObject candidateMetadata() throws JSONException {
            JSONArray result = new JSONArray();
            for (Candidate candidate : candidates) result.put(candidate.toJson());
            return new JSONObject().put("candidate_count", candidates.size())
                    .put("candidates", result)
                    .put("missing_parameters", new JSONArray(missingParameters))
                    .put("warnings", new JSONArray(warnings));
        }

        public String compareVlmAction(JSONObject action) {
            return compareVlmAction(action, null);
        }

        public String compareVlmAction(JSONObject action, String preferredCandidateId) {
            VlmMatch match = matchingVlmCandidate(action, find(preferredCandidateId));
            return match.candidate == null ? null : match.candidate.id;
        }

        /** Reports whether a VLM action has one complete, unambiguous candidate representation. */
        public String vlmCoverageStatus(JSONObject action) {
            return matchingVlmCandidate(action, null).status;
        }

        public Candidate candidateById(String id) {
            return find(id);
        }

        public String compareRecommendation(String choiceId, JSONObject action) {
            Candidate chosen = find(choiceId);
            if (choiceId != null && chosen == null) return "invalid_choice";
            if (action == null) return "not_compared";
            VlmMatch match = matchingVlmCandidate(action, chosen);
            if (match.candidate == null) return match.status;
            if (chosen == null) return "recommendation_pending";
            Candidate vlm = match.candidate;
            if (chosen.id.equals(vlm.id)) return "same_action";
            return sameActionParameters(chosen, vlm)
                    && chosen.equivalenceGroup.equals(vlm.equivalenceGroup)
                    ? "legal_equivalent_action" : "different_action";
        }

        private Candidate find(String id) {
            if (id == null) return null;
            for (Candidate candidate : candidates) if (candidate.id.equals(id)) return candidate;
            return null;
        }

        private VlmMatch matchingVlmCandidate(JSONObject action, Candidate preferred) {
            if (action == null) return new VlmMatch(null, "not_compared");
            String kind = action.optString("kind", "");
            JSONObject params = action.optJSONObject("parameters");
            if ("set_text".equals(kind)) {
                String nodeId = action.optString("target_node_id", "");
                String text = params == null ? "" : params.optString("text", "");
                List<Candidate> matches = new ArrayList<>();
                for (Candidate candidate : candidates) {
                    if ("input_text".equals(candidate.kind) && candidate.nodeId.equals(nodeId)
                            && text.equals(candidate.parameters.optString("text", ""))
                            && params != null && sameParameters(candidate.parameters, params)) matches.add(candidate);
                }
                return uniqueMatch(matches, preferred);
            }
            if ("system_back".equals(kind)) {
                JSONObject backParameters = params == null ? new JSONObject() : params;
                if (backParameters.length() != 0) return new VlmMatch(null, "vlm_candidate_missing");
                List<Candidate> matches = new ArrayList<>();
                for (Candidate candidate : candidates) {
                    if ("back".equals(candidate.kind) && candidate.parameters.length() == 0) matches.add(candidate);
                }
                return uniqueMatch(matches, preferred);
            }
            double x = params == null ? Double.NaN : params.optDouble("x", Double.NaN);
            double y = params == null ? Double.NaN : params.optDouble("y", Double.NaN);
            String candidateKind = "coordinate_tap".equals(kind) ? "tap"
                    : "long_press".equals(kind) ? "long_press" : "";
            if (!candidateKind.isEmpty() && Double.isFinite(x) && Double.isFinite(y)) {
                JSONObject actionParameters = new JSONObject();
                if ("long_press".equals(kind) && params.has("duration_ms")) {
                    try {
                        actionParameters.put("duration_ms", params.get("duration_ms"));
                    } catch (JSONException ignored) {
                        return new VlmMatch(null, "vlm_candidate_missing");
                    }
                }
                List<Candidate> matches = new ArrayList<>();
                for (Candidate candidate : candidates) {
                    if (candidateKind.equals(candidate.kind) && candidate.containsPoint(x, y)
                            && sameParameters(candidate.parameters, actionParameters)) matches.add(candidate);
                }
                return uniqueMatch(matches, preferred);
            }
            if ("swipe".equals(kind) && params != null) {
                double x1 = params.optDouble("x1", Double.NaN);
                double y1 = params.optDouble("y1", Double.NaN);
                double x2 = params.optDouble("x2", Double.NaN);
                double y2 = params.optDouble("y2", Double.NaN);
                if (!Double.isFinite(x1) || !Double.isFinite(y1)
                        || !Double.isFinite(x2) || !Double.isFinite(y2)) {
                    return new VlmMatch(null, "vlm_candidate_missing");
                }
                String direction = Math.abs(y2 - y1) >= Math.abs(x2 - x1)
                        ? (y2 < y1 ? "up" : "down") : (x2 < x1 ? "left" : "right");
                JSONObject actionParameters;
                try {
                    actionParameters = new JSONObject().put("direction", direction);
                } catch (JSONException ignored) {
                    return new VlmMatch(null, "vlm_candidate_missing");
                }
                List<Candidate> matches = new ArrayList<>();
                for (Candidate candidate : candidates) {
                    if ("scroll".equals(candidate.kind) && direction.equals(candidate.parameters.optString("direction"))
                            && candidate.containsPoint(x1, y1)
                            && sameParameters(candidate.parameters, actionParameters)) matches.add(candidate);
                }
                return uniqueMatch(matches, preferred);
            }
            return new VlmMatch(null, "vlm_candidate_missing");
        }

        private static VlmMatch uniqueMatch(List<Candidate> matches, Candidate preferred) {
            if (matches.isEmpty()) return new VlmMatch(null, "vlm_candidate_missing");
            if (preferred != null && matches.contains(preferred)) {
                if (matches.size() == 1) return new VlmMatch(preferred, "matched");
                return new VlmMatch(null, "vlm_candidate_ambiguous");
            }
            if (matches.size() > 1) return new VlmMatch(null, "vlm_candidate_ambiguous");
            Candidate match = matches.get(0);
            return new VlmMatch(match, "matched");
        }

        private static boolean sameActionParameters(Candidate left, Candidate right) {
            return left.kind.equals(right.kind) && sameParameters(left.parameters, right.parameters);
        }

        private static boolean sameParameters(JSONObject left, JSONObject right) {
            if (left.length() != right.length()) return false;
            java.util.Iterator<String> keys = left.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!right.has(key) || !sameValue(left.opt(key), right.opt(key))) return false;
            }
            return true;
        }

        private static boolean sameValue(Object left, Object right) {
            if (left instanceof Number && right instanceof Number) {
                return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue()) == 0;
            }
            return left == null ? right == null : left.equals(right);
        }

    }

    private static final class VlmMatch {
        final Candidate candidate;
        final String status;

        VlmMatch(Candidate candidate, String status) {
            this.candidate = candidate;
            this.status = status;
        }
    }

    private static final class ActionSpec {
        final String kind;
        final String parameter;
        final Object value;
        final String equivalenceGroup;
        String missingParameter;

        ActionSpec(String kind, String parameter, Object value, String equivalenceGroup) {
            this.kind = kind;
            this.parameter = parameter;
            this.value = value;
            this.equivalenceGroup = equivalenceGroup;
        }
    }
}
