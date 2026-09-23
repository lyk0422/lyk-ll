package com.example.starter.rosbridge.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 客户端通过 WebSocket 发来的请求帧。
 *
 * <p>支持的 op：
 * <ul>
 *   <li>SUBSCRIBE / UNSUBSCRIBE：订阅、取消 Topic（字段 topic/type/qos）</li>
 *   <li>ACK：确认游标（字段 cursor，确认该游标及之前的全部 Topic 消息）</li>
 *   <li>SERVICE_CALL：调用 Service（字段 requestId/service/args）</li>
 *   <li>ACTION_SEND_GOIL：提交 Action goal（字段 action/goalId/args）</li>
 *   <li>ACTION_CANCEL：取消 Action goal（字段 goalId）</li>
 * </ul>
 */
public class ClientRequest {

    /** 操作码，大写形式，例如 SUBSCRIBE。 */
    public String op;
    /** 客户端标识；正常情况下连接查询参数已携带，帧内字段仅作兜底校验。 */
    public String clientId;
    /** ROS 2 Topic 名称。 */
    public String topic;
    /** ROS 2 消息类型，例如 std_msgs/msg/String。 */
    public String type;
    /** QoS 配置（任意 JSON），由网关规范化后参与上游复用匹配。 */
    public JsonNode qos;
    /** 客户端已确认的最大单调游标。 */
    public Long cursor;
    /** Service 调用请求标识，由客户端保证唯一。 */
    public String requestId;
    /** ROS 2 Service 名称。 */
    public String service;
    /** Service 入参或 Action goal 参数。 */
    public JsonNode args;
    /** ROS 2 Action 名称。 */
    public String action;
    /** Action goal 标识，由客户端保证唯一。 */
    public String goalId;

    public String getOp() {
        return op;
    }

    public void setOp(String op) {
        this.op = op;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public JsonNode getQos() {
        return qos;
    }

    public void setQos(JsonNode qos) {
        this.qos = qos;
    }

    public Long getCursor() {
        return cursor;
    }

    public void setCursor(Long cursor) {
        this.cursor = cursor;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public JsonNode getArgs() {
        return args;
    }

    public void setArgs(JsonNode args) {
        this.args = args;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getGoalId() {
        return goalId;
    }

    public void setGoalId(String goalId) {
        this.goalId = goalId;
    }
}
