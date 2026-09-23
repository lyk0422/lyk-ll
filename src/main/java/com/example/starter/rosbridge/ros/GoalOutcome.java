package com.example.starter.rosbridge.ros;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Action goal 的最终结果。
 *
 * @param status SUCCEEDED=成功，ABORTED=异常终止
 * @param result 结果内容（JSON）
 */
public record GoalOutcome(String status, JsonNode result) {

    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String ABORTED = "ABORTED";

    public static GoalOutcome succeeded(JsonNode result) {
        return new GoalOutcome(SUCCEEDED, result);
    }

    public static GoalOutcome aborted(String error) {
        return new GoalOutcome(ABORTED, null);
    }
}
