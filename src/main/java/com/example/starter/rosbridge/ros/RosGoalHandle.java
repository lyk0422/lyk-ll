package com.example.starter.rosbridge.ros;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 适配器 -> 网关的 Action 回调句柄。所有方法均应线程安全、终态幂等：
 * 终态（succeed/fail/cancel）后的重复调用会被网关忽略。
 */
public interface RosGoalHandle {

    /** 网关内部使用的 goal 令牌，回传给 {@link RosAdapter#cancelGoal}。 */
    String goalToken();

    /** 上报一条 feedback（非终态）。 */
    void publishFeedback(JsonNode feedback);

    /** 上报成功最终结果（终态）。 */
    void succeed(JsonNode result);

    /** 上报失败最终结果（终态）。 */
    void fail(String errorMessage, JsonNode result);

    /** 上报取消完成（终态）。 */
    void cancelled(JsonNode result);
}
