package com.example.starter.rosbridge.adapter;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 可注入的 ROS 2 后端适配器。网关不依赖真实 ROS，
 * 本地默认装配 {@link SyntheticRosAdapter}，测试中可替换为自定义桩。
 *
 * <p>所有调用均为异步风格：结果通过回调返回，超时与幂等由网关负责。
 */
public interface RosAdapter {

    /**
     * 在 ROS 侧建立一个 Topic 上游订阅。同 topic/type/qos 的多个网关订阅
     * 是否复用由网关引用计数控制，适配器每次调用对应一个真实上游订阅。
     *
     * @param topic    Topic 名称
     * @param type     消息类型
     * @param qos      规范化后的 QoS 参数
     * @param listener 消息回调（适配器线程触发，需保证线程安全）
     * @return 上游订阅句柄，{@link TopicHandle#close()} 时释放 ROS 侧资源
     */
    TopicHandle subscribeTopic(String topic, String type, Map<String, Object> qos, TopicListener listener);

    /**
     * 异步调用 ROS 2 Service。完成时（成功或失败）恰好触发一次回调。
     */
    void callService(String service, JsonNode args, ServiceCallback callback);

    /**
     * 提交 ROS 2 Action goal。
     */
    GoalHandle sendGoal(String action, JsonNode goal, GoalListener listener);

    /** Topic 上游订阅句柄。 */
    interface TopicHandle {
        /** 释放上游订阅；重复调用幂等。 */
        void close();
    }

    /** Topic 消息监听。 */
    interface TopicListener {
        void onMessage(JsonNode message);
    }

    /** Service 异步回调。 */
    interface ServiceCallback {
        void onSuccess(JsonNode result);

        void onFailure(String code, String message);
    }

    /** Action goal 句柄。 */
    interface GoalHandle {
        /** 请求取消 goal；重复调用幂等。取消结果以 GoalListener 的最终结果为准。 */
        void cancel();
    }

    /** Action goal 监听。 */
    interface GoalListener {
        void onFeedback(JsonNode feedback);

        void onResult(ActionOutcome outcome);
    }

    /** Action 最终结果。 */
    record ActionOutcome(String status, JsonNode result) {
    }
}
