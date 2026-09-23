package com.example.starter.rosbridge.ros;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Consumer;

/**
 * 可注入的 ROS 2 适配层。网关不依赖真实 ROS，生产环境可替换为 rcljava / 进程桥接实现，
 * 测试与本地启动使用合成实现。
 */
public interface RosAdapter {

    /**
     * 建立一个上游 Topic 订阅。
     *
     * @param topic    Topic 名称
     * @param type     消息类型（如 std_msgs/String）
     * @param qos      QoS 策略
     * @param messageSink 上游消息回调，参数为消息体 JSON
     * @return 用于释放上游订阅的句柄
     */
    AutoCloseable subscribe(String topic, String type, Qos qos, Consumer<JsonNode> messageSink);

    /**
     * 发起一次 Service 调用，完成时回调（可能成功或失败）。适配器不感知超时，
     * 超时由网关判定，迟到回调必须被忽略。
     */
    void callService(String service, String type, JsonNode args, Consumer<ServiceOutcome> callback);

    /**
     * 提交一个 Action goal。
     *
     * @param feedbackSink feedback 回调（可多次）
     * @param resultSink   最终结果回调（仅一次）
     * @return goal 句柄，可用于取消
     */
    GoalHandle sendGoal(String action, String type, JsonNode goal,
                        Consumer<JsonNode> feedbackSink, Consumer<GoalOutcome> resultSink);
}
