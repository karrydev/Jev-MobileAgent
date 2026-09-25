package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android port of the extracted MobileAgent v3.5 role prompts and loop state.
 * Source: agent_core/vlm/roles.py and orchestration.py, derived from upstream
 * Mobile-Agent commit 11cea575561fb7800b5fb6b6cafa56f7a91de11f (Apache-2.0).
 * The production action boundary intentionally keeps only the Android actions
 * already implemented by ObservationAccessibilityService.
 */
public final class MobileAgentVlmRoles {
    private static final Pattern CURRENT_GOAL_SPLIT = Pattern.compile("(?<=\\d)\\. ");
    private static final Pattern REFLECTION_OUTCOME_LABEL = Pattern.compile("^([ABC])(?:\\s*[:：].*)?$");
    private static final String COORDINATE_NOTE =
            "\n\n---\n### Bounded baseline coordinate convention ###\n"
                    + "For coordinate and coordinate2 values, use normalized x/y values from 0 to 1000.";

    private static final String DEFAULT_EXECUTOR_GUIDELINES =
            "General:\n"
                    + "- For any pop-up window, such as a permission request, you need to close it (e.g., by clicking `Don't Allow` or `Accept & continue`) before proceeding. Never choose to add any account or log in.\n"
                    + "- For requests that are questions (or chat messages), remember to use the `answer` action to reply to user explicitly before finish!\n"
                    + "- If the desired state is already achieved (e.g., enabling Wi-Fi when it's already on), you can just complete the task.\n"
                    + "Action Related:\n"
                    + "- Use the `open_app` action whenever you want to open an app (nothing will happen if the app is not installed), do not use the app drawer to open an app.\n"
                    + "- Consider exploring the screen by using the `swipe` action with different directions to reveal additional content. Or use search to quickly find a specific entry, if applicable.\n"
                    + "- If you cannot change the page content by swiping in the same direction continuously, the page may have been swiped to the bottom. Please try another operation to display more content.\n"
                    + "- For some horizontally distributed tags, you can swipe horizontally to view more.\n"
                    + "Text Related Operations:\n"
                    + "- Activated input box: If an input box is activated, it may have a cursor inside it and the keyboard is visible. If there is no cursor on the screen but the keyboard is visible, it may be because the cursor is blinking. The color of the activated input box will be highlighted. If you are not sure whether the input box is activated, click it before typing.\n"
                    + "- To input some text: first click the input box that you want to input, make sure the correct input box is activated and the keyboard is visible, then use `type` action to enter the specified text.\n"
                    + "- To clear the text: long press the backspace button in the keyboard.\n"
                    + "- To copy some text: first long press the text you want to copy, then click the `copy` button in bar.\n"
                    + "- To paste text into a text box: first long press the text box, then click the `paste` button in bar.";

    private static final String[][] ACTION_SIGNATURES = {
            {"answer", "text", "Answer user's question. Usage example: {\"action\": \"answer\", \"text\": \"the content of your answer\"}"},
            {"click", "coordinate", "Click the point on the screen with specified (x, y) coordinates. Usage Example: {\"action\": \"click\", \"coordinate\": [x, y]}"},
            {"long_press", "coordinate", "Long press on the position (x, y) on the screen. Usage Example: {\"action\": \"long_press\", \"coordinate\": [x, y]}"},
            {"type", "text", "Type text into current activated input box or text field. There may be a cursor in the activated input box. If not, click the input box to confirm again. Please make sure the correct input box has been activated before typing. Usage Example: {\"action\": \"type\", \"text\": \"the text you want to type\"}"},
            {"system_button", "button", "Press a system button, including back, home, and enter. Usage example: {\"action\": \"system_button\", \"button\": \"Home\"}"},
            {"swipe", "coordinate, coordinate2", "Scroll from the position with coordinate to the position with coordinate2. Please make sure the start and end points of your swipe are within the swipeable area and away from the keyboard. Usage Example: {\"action\": \"swipe\", \"coordinate\": [x1, y1], \"coordinate2\": [x2, y2]}"},
            {"open_app", "text", "Open an app. Usage example: {\"action\": \"open_app\", \"text\": \"the name of app\"}"}
    };

    public String instruction = "";
    public String plan = "";
    public String completedPlan = "No completed subgoal.";
    public String progressStatus = "";
    public String lastAnswer = "";
    public String importantNotes = "";
    public String lastSummary = "";
    public String lastActionThought = "";
    /** Current uncommitted action supplied to the reflector before it enters history. */
    private String actionForReflection = "";
    public String finishThought = "";
    public boolean errorFlagPlan;
    public boolean noteRequested;
    public final List<String> actionHistory = new ArrayList<>();
    public final List<String> summaryHistory = new ArrayList<>();
    public final List<String> actionOutcomes = new ArrayList<>();
    public final List<String> errorDescriptions = new ArrayList<>();

    public String managerPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an agent who can operate an Android phone on behalf of a user. ")
                .append("Your goal is to track progress and devise high-level plans to achieve ")
                .append("the user's requests.\n\n");
        prompt.append("### User Request ###\n").append(instruction).append("\n\n");
        if (plan.isEmpty()) {
            prompt.append("---\nMake a high-level plan to achieve the user's request. If the request ")
                    .append("is complex, break it down into subgoals. The screenshot displays the ")
                    .append("starting state of the phone.\n")
                    .append("IMPORTANT: For requests that explicitly require an answer, always add ")
                    .append("'perform the `answer` action' as the last step to the plan! Please use open_app to open an app instead of the app drawer.\n\n")
                    .append("### Guidelines ###\nThe following guidelines will help you plan this request.\n")
                    .append("General:\n1. Use the `open_app` action whenever you want to open an app, do not use the app drawer to open an app.\n")
                    .append("2. Use search to quickly find a file or entry with a specific name, if search function is applicable.\n")
                    .append("Task-specific:\n[add_info]\n\n")
                    .append("Provide your output in the following format which contains two parts:\n")
                    .append("### Thought ###\nA detailed explanation of your rationale for the plan and subgoals.\n\n")
                    .append("### Plan ###\n1. first subgoal\n2. second subgoal\n...");
        } else {
            if (!"No completed subgoal.".equals(completedPlan)) {
                prompt.append("### Historical Operations ###\nOperations that have been completed before:\n")
                        .append(completedPlan).append("\n\n");
            }
            prompt.append("### Plan ###\n").append(plan).append("\n\n")
                    .append("### Last Action ###\n").append(lastAction()).append("\n\n")
                    .append("### Last Action Description ###\n").append(lastSummary).append("\n\n")
                    .append("### Important Notes ###\n")
                    .append(importantNotes.isEmpty() ? "No important notes recorded.\n\n" : importantNotes + "\n\n")
                    .append("### Guidelines ###\nThe following guidelines will help you plan this request.\n")
                    .append("General:\n1. Use the `open_app` action whenever you want to open an app, do not use the app drawer to open an app.\n")
                    .append("2. Use search to quickly find a file or entry with a specific name, if search function is applicable.\n")
                    .append("Task-specific:\n[add_info]\n\n");
            if (errorFlagPlan) {
                prompt.append("### Potentially Stuck! ###\nYou have encountered several failed attempts. Here are some logs:\n");
                int start = Math.max(0, actionHistory.size() - 2);
                for (int i = start; i < actionHistory.size(); i++) {
                    prompt.append("- Attempt: Action: ").append(actionHistory.get(i))
                            .append(" | Description: ").append(at(summaryHistory, i))
                            .append(" | Outcome: Failed | Feedback: ").append(at(errorDescriptions, i)).append('\n');
                }
            }
            prompt.append("---\nCarefully assess the current status and the provided screenshot. Check if the current plan needs to be revised.\n")
                    .append("Determine if the user request has been fully completed. If you are confident that no further actions are required, mark the plan as \"Finished\" in your output. If the user request is not finished, update the plan. If you are stuck with errors, think step by step about whether the overall plan needs to be revised to address the error.\n")
                    .append("NOTE: 1. If the current situation prevents proceeding with the original plan or requires clarification from the user, make reasonable assumptions and revise the plan accordingly. Act as though you are the user in such cases. 2. Please refer to the helpful information and steps in the Guidelines first for planning. 3. If the first subgoal in plan has been completed, please update the plan in time according to the screenshot and progress to ensure that the next subgoal is always the first item in the plan. 4. If the first subgoal is not completed, please copy the previous round's plan or update the plan based on the completion of the subgoal.\n")
                    .append("IMPORTANT: If the next steps require an `answer` action, make sure that there is a plan to perform the `answer` action. In this case, you should not mark the plan as \"Finished\" unless the last action is `answer`.\n")
                    .append("Provide your output in the following format, which contains three parts:\n\n")
                    .append("### Thought ###\nAn explanation of your rationale for the updated plan and current subgoal.\n\n")
                    .append("### Historical Operations ###\nTry to add the most recently completed subgoal on top of the existing historical operations. Please do not delete any existing historical operation. If there is no newly completed subgoal, just copy the existing historical operations.\n\n")
                    .append("### Plan ###\nPlease update or copy the existing plan according to the current page and progress. Please pay close attention to the historical operations. Please do not repeat the plan of completed content unless you can judge from the screen status that a subgoal is indeed not completed.");
        }
        return prompt.toString();
    }

    public String executorPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an agent who can operate an Android phone on behalf of a user. ")
                .append("Your goal is to decide the next action to perform based on the current ")
                .append("state of the phone and the user's request.\n\n")
                .append("### User Request ###\n").append(instruction).append("\n\n")
                .append("### Overall Plan ###\n").append(plan).append("\n\n")
                .append("### Current Subgoal ###\n").append(currentGoal(plan)).append("\n\n")
                .append("### Progress Status ###\n")
                .append(progressStatus.isEmpty() ? "No progress yet.\n\n" : progressStatus + "\n\n")
                .append("### Guidelines ###\n").append(DEFAULT_EXECUTOR_GUIDELINES).append("\n\n---\n")
                .append("Carefully examine all the information provided above and decide on the next action to perform. If you notice an unsolved error in the previous action, think as a human user and attempt to rectify them. You must choose your action from one of the atomic actions.\n\n")
                .append("#### Atomic Actions ####\nThe atomic action functions are listed in the format of `action(arguments): description` as follows:\n");
        for (String[] signature : ACTION_SIGNATURES) {
            prompt.append("- ").append(signature[0]).append('(').append(signature[1]).append("): ")
                    .append(signature[2]).append('\n');
        }
        prompt.append("\n### Latest Action History ###\n");
        if (actionHistory.isEmpty()) {
            prompt.append("No actions have been taken yet.\n\n");
        } else {
            prompt.append("Recent actions you took previously and whether they were successful:\n");
            int start = Math.max(0, actionHistory.size() - 5);
            for (int i = start; i < actionHistory.size(); i++) {
                prompt.append("Action: ").append(actionHistory.get(i))
                        .append(" | Description: ").append(at(summaryHistory, i));
                if ("A".equals(at(actionOutcomes, i))) {
                    prompt.append(" | Outcome: Successful\n");
                } else {
                    prompt.append(" | Outcome: Failed | Feedback: ").append(at(errorDescriptions, i)).append('\n');
                }
            }
            prompt.append('\n');
        }
        return prompt.append("---\nIMPORTANT:\n1. Do NOT repeat previously failed actions multiple times. Try changing to another action.\n2. Please prioritize the current subgoal.\n\n")
                .append("Provide your output in the following format, which contains three parts:\n")
                .append("### Thought ###\nProvide a detailed explanation of your rationale for the chosen action.\n\n")
                .append("### Action ###\nChoose only one action or shortcut from the options provided.\n")
                .append("You must provide your decision using a valid JSON format specifying the `action` and the arguments of the action. For example, if you want to open an App, you should write {\"action\":\"open_app\", \"text\": \"app name\"}.\n\n")
                .append("### Description ###\nA brief description of the chosen action. Do not describe expected outcome.").toString();
    }

    public String reflectorPrompt() {
        return "You are an agent who can operate an Android phone on behalf of a user. Your goal is to verify whether the last action produced the expected behavior and to keep track of the overall progress.\n\n"
                + "### User Request ###\n" + instruction + "\n\n"
                + "### Progress Status ###\n" + (completedPlan.isEmpty() ? "No progress yet.\n\n" : completedPlan + "\n\n")
                + "---\nThe two attached images are phone screenshots taken before and after your last action. \n---\n"
                + "### Latest Action ###\nAction: " + latestActionForReflection() + "\nExpectation: " + lastSummary + "\n\n---\n"
                + "Carefully examine the information provided above to determine whether the last action produced the expected behavior. If the action was successful, update the progress status accordingly. If the action failed, identify the failure mode and provide reasoning on the potential reason causing this failure.\n\n"
                + "Note: For swiping to scroll the screen to view more content, if the content displayed before and after the swipe is exactly the same, the swipe is considered to be C: Failed. The last action produces no changes. This may be because the content has been scrolled to the bottom.\n\n"
                + "Provide your output in the following format containing two parts:\n"
                + "### Outcome ###\nChoose from the following options. Give your response as \"A\", \"B\" or \"C\":\n"
                + "A: Successful or Partially Successful. The result of the last action meets the expectation.\n"
                + "B: Failed. The last action results in a wrong page. I need to return to the previous state.\n"
                + "C: Failed. The last action produces no changes.\n\n"
                + "### Error Description ###\nIf the action failed, provide a detailed description of the error and the potential reason causing this failure. If the action succeeded, put \"None\" here.";
    }

    public String notetakerPrompt() {
        return "You are a helpful AI assistant for operating mobile phones. Your goal is to take notes of important content relevant to the user's request.\n\n"
                + "### User Request ###\n" + instruction + "\n\n"
                + "### Progress Status ###\n" + progressStatus + "\n\n"
                + "### Existing Important Notes ###\n"
                + (importantNotes.isEmpty() ? "No important notes recorded.\n\n" : importantNotes + "\n\n")
                + "---\nCarefully examine the information above to identify any important content on the current screen that needs to be recorded.\n"
                + "IMPORTANT:\nDo not take notes on low-level actions; only keep track of significant textual or visual information relevant to the user's request. Do not repeat user request or progress status. Do not make up content that you are not sure about.\n\n"
                + "Provide your output in the following format:\n### Important Notes ###\nThe updated important notes, combining the old and new ones. If nothing new to record, copy the existing important notes.";
    }

    public String[] parseManager(String response) {
        boolean historical = response.contains("### Historical Operations");
        String thought = section(response, "### Thought", historical ? "### Historical Operations" : "### Plan");
        String completed = historical
                ? section(response, "### Historical Operations", "### Plan")
                : "No completed subgoal.";
        String updatedPlan = after(response, "### Plan");
        return new String[] {clean(thought), clean(completed), clean(updatedPlan)};
    }

    public String[] parseExecutor(String response) {
        return new String[] {
                clean(section(response, "### Thought", "### Action")),
                clean(section(response, "### Action", "### Description")),
                clean(after(response, "### Description"))
        };
    }

    public String[] parseReflection(String response) {
        return new String[] {
                clean(section(response, "### Outcome", "### Error Description")),
                clean(after(response, "### Error Description"))
        };
    }

    static String reflectionOutcomeLabel(String value) {
        Matcher matcher = REFLECTION_OUTCOME_LABEL.matcher(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        return matcher.matches() ? matcher.group(1) : "";
    }

    public String parseNotes(String response) {
        return clean(after(response, "### Important Notes"));
    }

    public JSONArray userContent(String prompt, JSONArray screenshots) throws JSONException {
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "text")
                .put("text", prompt == null ? "" : prompt.trim() + COORDINATE_NOTE));
        if (screenshots != null) {
            for (int i = 0; i < screenshots.length(); i++) {
                JSONObject screenshot = screenshots.optJSONObject(i);
                String data = screenshot == null ? "" : screenshot.optString("png_base64", "");
                if (data.isEmpty()) {
                    throw new JSONException("required screenshot is unavailable");
                }
                content.put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", "data:image/png;base64," + data)));
            }
        }
        return content;
    }

    public ActionCommand action(String actionText, JSONObject observation,
            String taskId, int sequence, String beforeScreenshotId) throws JSONException {
        JSONObject raw;
        try {
            raw = new JSONObject(actionText);
        } catch (JSONException exception) {
            throw new JSONException("invalid action JSON");
        }
        String kind = raw.optString("action", "");
        if (kind.isEmpty()) {
            throw new JSONException("action JSON must contain an action string");
        }
        if ("answer".equals(kind)) {
            Object value = raw.opt("text");
            if (!(value instanceof String)) {
                throw new JSONException("answer requires string text");
            }
            String answerText = ((String) value).trim();
            if (answerText.isEmpty()) {
                throw new JSONException("answer requires non-empty text");
            }
            return new ActionCommand(raw, null, true, true, answerText);
        }
        if ("done".equals(kind) || "terminate".equals(kind)) {
            return new ActionCommand(raw, null, true);
        }

        JSONObject screen = observation.optJSONObject("screen");
        if (screen == null) {
            throw new JSONException("observation screen frame is missing");
        }
        int width = screen.optInt("width_px", 0);
        int height = screen.optInt("height_px", 0);
        if (width < 1 || height < 1) {
            throw new JSONException("observation screen dimensions are invalid");
        }
        String androidKind;
        JSONObject parameters = new JSONObject();
        String targetId = "";
        String targetLabel = "";
        String targetRole = "visual_surface";
        switch (kind) {
            case "click": {
                JSONArray coordinate = requiredPoint(raw, "coordinate");
                androidKind = "coordinate_tap";
                parameters.put("x", pixel(coordinate.optDouble(0, Double.NaN), width));
                parameters.put("y", pixel(coordinate.optDouble(1, Double.NaN), height));
                break;
            }
            case "long_press": {
                JSONArray coordinate = requiredPoint(raw, "coordinate");
                androidKind = "long_press";
                parameters.put("x", pixel(coordinate.optDouble(0, Double.NaN), width));
                parameters.put("y", pixel(coordinate.optDouble(1, Double.NaN), height));
                parameters.put("duration_ms", 700);
                break;
            }
            case "swipe": {
                JSONArray start = requiredPoint(raw, "coordinate");
                JSONArray end = requiredPoint(raw, "coordinate2");
                androidKind = "swipe";
                parameters.put("x1", pixel(start.optDouble(0, Double.NaN), width));
                parameters.put("y1", pixel(start.optDouble(1, Double.NaN), height));
                parameters.put("x2", pixel(end.optDouble(0, Double.NaN), width));
                parameters.put("y2", pixel(end.optDouble(1, Double.NaN), height));
                parameters.put("duration_ms", 600);
                break;
            }
            case "type": {
                String text = raw.optString("text", null);
                if (text == null) {
                    throw new JSONException("type requires text");
                }
                JSONObject focused = focusedEditable(observation);
                if (focused == null) {
                    throw new JSONException("type requires a focused editable node in the current observation");
                }
                androidKind = "set_text";
                parameters.put("text", text);
                targetId = focused.optString("node_id", "");
                targetLabel = focused.optString("content_description", "");
                if (targetLabel.isEmpty()) {
                    targetLabel = focused.optString("text", "");
                }
                targetRole = "edit_text";
                break;
            }
            case "system_button": {
                String button = raw.optString("button", "").toLowerCase(Locale.ROOT);
                if (!"back".equals(button)) {
                    throw new JSONException("only system Back is available in the Android action contract");
                }
                androidKind = "system_back";
                targetRole = "system_navigation";
                break;
            }
            case "open_app":
                throw new JSONException("open_app is not available in the Android action contract");
            default:
                throw new JSONException("unsupported action: " + kind);
        }

        JSONObject action = new JSONObject();
        JSONObject frame = new JSONObject(screen.toString())
                .put("screen_width_px", width)
                .put("screen_height_px", height)
                .put("model_width_px", width)
                .put("model_height_px", height)
                .put("coordinate_space", "screen");
        action.put("schema_version", "1.0")
                .put("android_schema_version", "1.0")
                .put("task_id", taskId)
                .put("device_id", observation.optString("device_id", "local-device"))
                .put("action_id", "local-vlm-action-" + taskId + "-" + sequence)
                .put("observation_id", observation.optString("observation_id", ""))
                .put("observation_version", observation.optLong("observation_version", 0L))
                .put("sequence", sequence)
                .put("kind", androidKind)
                .put("target_node_id", targetId)
                .put("target_node_label", targetLabel)
                .put("target_node_role", targetRole)
                .put("expected_page_state", androidKind + "_requested")
                .put("parameters", parameters)
                .put("coordinate_frame", frame)
                .put("requires_screenshot", true)
                .put("before_screenshot_id", beforeScreenshotId == null ? JSONObject.NULL : beforeScreenshotId)
                .put("source", "mobileagent-v3.5-vlm-role")
                .put("created_at", java.time.Instant.now().toString());
        return new ActionCommand(raw, action, false);
    }

    /** Builds the same observation-bound action envelope for a Jev-selected live candidate. */
    public ActionCommand actionForCandidate(JevCandidateBuilder.Candidate candidate,
            JSONObject observation, String taskId, int sequence, String beforeScreenshotId)
            throws JSONException {
        if (candidate == null || observation == null) {
            throw new JSONException("Jev candidate and current observation are required");
        }
        JSONObject screen = observation.optJSONObject("screen");
        int width = screen == null ? 0 : screen.optInt("width_px", 0);
        int height = screen == null ? 0 : screen.optInt("height_px", 0);
        if (width < 1 || height < 1 || candidate.nodeId == null || candidate.nodeId.isEmpty()) {
            throw new JSONException("Jev candidate is missing its current target or screen frame");
        }

        JSONObject sourceNode = candidate.sourceNodeSnapshot();
        if (sourceNode == null || !candidate.nodeId.equals(sourceNode.optString("node_id", ""))) {
            throw new JSONException("Jev candidate is not bound to an observed node");
        }
        String contractKind;
        JSONObject parameters = new JSONObject(candidate.parameters.toString());
        switch (candidate.kind) {
            case "tap":
                contractKind = "tap";
                break;
            case "input_text":
                contractKind = "set_text";
                if (!(parameters.opt("text") instanceof String)
                        || parameters.optString("text", "").isEmpty()) {
                    throw new JSONException("Jev text candidate is missing its known text");
                }
                break;
            case "back":
                contractKind = "system_back";
                parameters = new JSONObject();
                break;
            case "long_press":
                contractKind = "long_press";
                break;
            case "scroll":
                contractKind = "swipe";
                parameters = swipeParameters(candidate, sourceNode, width, height);
                break;
            default:
                throw new JSONException("unsupported Jev candidate kind");
        }

        String targetLabel = sourceNode.optString("content_description", "");
        if (targetLabel.isEmpty()) targetLabel = sourceNode.optString("text", "");
        String targetRole = sourceNode.optString("role", sourceNode.optString("class_name", ""));
        String targetId = "system_back".equals(contractKind) ? "" : candidate.nodeId;
        JSONObject frame = new JSONObject(screen.toString())
                .put("screen_width_px", width)
                .put("screen_height_px", height)
                .put("model_width_px", width)
                .put("model_height_px", height)
                .put("coordinate_space", "screen");
        JSONObject action = new JSONObject()
                .put("schema_version", "1.0")
                .put("android_schema_version", "1.0")
                .put("task_id", taskId)
                .put("device_id", observation.optString("device_id", "local-device"))
                .put("action_id", "local-vlm-action-" + taskId + "-" + sequence)
                .put("observation_id", observation.optString("observation_id", ""))
                .put("observation_version", observation.optLong("observation_version", 0L))
                .put("sequence", sequence)
                .put("kind", contractKind)
                .put("target_node_id", targetId)
                .put("target_node_label", targetLabel)
                .put("target_node_role", targetRole)
                .put("expected_page_state", contractKind + "_requested")
                .put("parameters", parameters)
                .put("coordinate_frame", frame)
                .put("requires_screenshot", true)
                .put("before_screenshot_id", beforeScreenshotId == null
                        ? JSONObject.NULL : beforeScreenshotId)
                .put("source", "jev-controlled-candidate-v1")
                .put("selection_source", "jev")
                .put("jev_candidate_id", candidate.id)
                .put("created_at", java.time.Instant.now().toString());
        JSONObject original = new JSONObject()
                .put("action", "jev_candidate")
                .put("candidate_id", candidate.id)
                .put("candidate_kind", candidate.kind)
                .put("target_node_id", candidate.nodeId)
                .put("description", candidate.description)
                .put("parameters", new JSONObject(parameters.toString()));
        return new ActionCommand(original, action, false);
    }

    private static JSONObject swipeParameters(JevCandidateBuilder.Candidate candidate,
            JSONObject sourceNode, int screenWidth, int screenHeight) throws JSONException {
        JSONObject bounds = sourceNode.optJSONObject("bounds");
        if (bounds == null) throw new JSONException("Jev scroll candidate has no observed bounds");
        double left = Math.max(0.0, bounds.optDouble("left", Double.NaN));
        double top = Math.max(0.0, bounds.optDouble("top", Double.NaN));
        double right = Math.min(screenWidth - 1.0, bounds.optDouble("right", Double.NaN));
        double bottom = Math.min(screenHeight - 1.0, bounds.optDouble("bottom", Double.NaN));
        if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(right)
                || !Double.isFinite(bottom) || right <= left || bottom <= top) {
            throw new JSONException("Jev scroll candidate bounds are invalid for this screen");
        }
        String direction = candidate.parameters.optString("direction", "");
        double x1 = (left + right) / 2.0;
        double y1 = (top + bottom) / 2.0;
        double x2 = x1;
        double y2 = y1;
        switch (direction) {
            case "up":
                y1 = bottom - Math.max(1.0, (bottom - top) / 5.0);
                y2 = top + Math.max(1.0, (bottom - top) / 5.0);
                break;
            case "down":
                y1 = top + Math.max(1.0, (bottom - top) / 5.0);
                y2 = bottom - Math.max(1.0, (bottom - top) / 5.0);
                break;
            case "left":
                x1 = right - Math.max(1.0, (right - left) / 5.0);
                x2 = left + Math.max(1.0, (right - left) / 5.0);
                break;
            case "right":
                x1 = left + Math.max(1.0, (right - left) / 5.0);
                x2 = right - Math.max(1.0, (right - left) / 5.0);
                break;
            default:
                throw new JSONException("Jev scroll candidate direction is invalid");
        }
        return new JSONObject().put("x1", Math.round(x1)).put("y1", Math.round(y1))
                .put("x2", Math.round(x2)).put("y2", Math.round(y2)).put("duration_ms", 600L);
    }

    public void recordInvalid(String summary, String reason) {
        actionHistory.add("{\"action\":\"invalid\"}");
        summaryHistory.add(summary == null ? "" : summary);
        actionOutcomes.add("C");
        errorDescriptions.add(reason == null ? "invalid action format; no device action was sent" : reason);
    }

    public void recordAction(JSONObject raw, String summary, String outcome, String error) {
        actionHistory.add(raw == null ? "{}" : raw.toString());
        summaryHistory.add(summary == null ? "" : summary);
        actionOutcomes.add(outcome == null ? "C" : outcome);
        errorDescriptions.add(error == null ? "" : error);
        progressStatus = completedPlan;
    }

    public void recordAnswer(ActionCommand command, String summary) {
        if (command == null || !command.answer) {
            throw new IllegalArgumentException("recordAnswer requires an answer action");
        }
        lastAnswer = command.answerText;
        actionHistory.add(command.original.toString());
        summaryHistory.add(summary == null ? "" : summary);
        actionOutcomes.add("A");
        errorDescriptions.add("None");
        progressStatus = completedPlan
                + "\nThe `answer` action has been performed. Answer to the question: "
                + lastAnswer;
    }

    void setActionForReflection(JSONObject raw) {
        actionForReflection = raw == null ? "" : raw.toString();
    }

    void clearActionForReflection() {
        actionForReflection = "";
    }

    public JSONObject toJson() {
        JSONObject result = new JSONObject();
        try {
            result.put("instruction", instruction)
                    .put("plan", plan)
                    .put("completed_plan", completedPlan)
                    .put("progress_status", progressStatus)
                    .put("last_answer", lastAnswer)
                    .put("important_notes", importantNotes)
                    .put("last_summary", lastSummary)
                    .put("last_action_thought", lastActionThought)
                    .put("finish_thought", finishThought)
                    .put("error_flag_plan", errorFlagPlan)
                    .put("note_requested", noteRequested)
                    .put("action_history", new JSONArray(actionHistory))
                    .put("summary_history", new JSONArray(summaryHistory))
                    .put("action_outcomes", new JSONArray(actionOutcomes))
                    .put("error_descriptions", new JSONArray(errorDescriptions));
        } catch (JSONException ignored) {
            // These in-memory values are JSON strings/scalars; no write can fail here.
        }
        return result;
    }

    public void restore(JSONObject value) {
        if (value == null) {
            return;
        }
        instruction = value.optString("instruction", "");
        plan = value.optString("plan", "");
        completedPlan = value.optString("completed_plan", "No completed subgoal.");
        progressStatus = value.optString("progress_status", "");
        lastAnswer = value.optString("last_answer", "");
        importantNotes = value.optString("important_notes", "");
        lastSummary = value.optString("last_summary", "");
        lastActionThought = value.optString("last_action_thought", "");
        finishThought = value.optString("finish_thought", "");
        errorFlagPlan = value.optBoolean("error_flag_plan", false);
        noteRequested = value.optBoolean("note_requested", false);
        restoreList(actionHistory, value.optJSONArray("action_history"));
        restoreList(summaryHistory, value.optJSONArray("summary_history"));
        restoreList(actionOutcomes, value.optJSONArray("action_outcomes"));
        restoreList(errorDescriptions, value.optJSONArray("error_descriptions"));
    }

    private String lastAction() {
        return actionHistory.isEmpty() ? "" : actionHistory.get(actionHistory.size() - 1);
    }

    private String latestActionForReflection() {
        return actionForReflection.isEmpty() ? lastAction() : actionForReflection;
    }

    private static JSONObject focusedEditable(JSONObject observation) {
        JSONArray nodes = observation.optJSONArray("nodes");
        if (nodes == null) {
            return null;
        }
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null && node.optBoolean("focused", false) && node.optBoolean("editable", false)
                    && node.optBoolean("enabled", false) && node.optBoolean("visible_to_user", false)) {
                return node;
            }
        }
        return null;
    }

    private static JSONArray requiredPoint(JSONObject action, String field) throws JSONException {
        JSONArray point = action.optJSONArray(field);
        if (point == null || point.length() != 2) {
            throw new JSONException(field + " requires [x, y]");
        }
        return point;
    }

    private static int pixel(double value, int size) throws JSONException {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0 || value > 1000.0 || size < 1) {
            throw new JSONException("coordinates must be finite values from 0 to 1000");
        }
        return Math.min(size - 1, (int) (value / 1000.0 * size));
    }

    private static String currentGoal(String plan) {
        String[] goals = CURRENT_GOAL_SPLIT.split(plan == null ? "" : plan);
        int count = Math.min(4, goals.length);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                current.append(". ");
            }
            current.append(goals[i]);
        }
        current.append('.');
        String value = current.toString();
        return value.length() >= 2 ? value.substring(0, value.length() - 2).trim() : value.trim();
    }

    private static String section(String source, String startMarker, String endMarker) {
        String value = source == null ? "" : source;
        int start = value.lastIndexOf(startMarker);
        if (start < 0) {
            return "";
        }
        start += startMarker.length();
        int end = value.indexOf(endMarker, start);
        return value.substring(start, end < 0 ? value.length() : end);
    }

    private static String after(String source, String marker) {
        String value = source == null ? "" : source;
        int index = value.lastIndexOf(marker);
        return index < 0 ? "" : value.substring(index + marker.length());
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace("  ", " ").replace("###", "").trim();
    }

    private static String at(List<String> values, int index) {
        return index >= 0 && index < values.size() ? values.get(index) : "";
    }

    private static void restoreList(List<String> target, JSONArray source) {
        target.clear();
        if (source == null) {
            return;
        }
        for (int i = 0; i < source.length(); i++) {
            target.add(source.optString(i, ""));
        }
    }

    public static final class ActionCommand {
        public final JSONObject original;
        public final JSONObject contractAction;
        public final boolean terminal;
        public final boolean answer;
        public final String answerText;

        private ActionCommand(JSONObject original, JSONObject contractAction, boolean terminal) {
            this(original, contractAction, terminal, false, "");
        }

        private ActionCommand(JSONObject original, JSONObject contractAction, boolean terminal,
                boolean answer, String answerText) {
            this.original = original;
            this.contractAction = contractAction;
            this.terminal = terminal;
            this.answer = answer;
            this.answerText = answerText;
        }
    }
}
