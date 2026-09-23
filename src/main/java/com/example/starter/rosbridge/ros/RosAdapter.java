package com.example.starter.rosbridge.ros;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 可注入的 ROS 2 适配层 SPI。网关只依赖该接口，默认提供合成实现，
 * 生产环境可替换为对接真实 ros2genny/rcljava 的 Bean，无需改动协议与状态管理代码。
 */
public interface RosAdapter {

    /**
     * 建立一个上游 Topic 订阅（引用计数归并后，每个 {@link SubscriptionKey} 只会调用一次）。
     *
     * @param key      订阅去重键
     * @param listener 上游消息回调，参数为消息内容 JSON 节点；回调可在任意线程触发
     * @return 上游订阅句柄，{@link RosSubscription#close()} 时真正释放上游资源
     */
    RosSubscription subscribe(SubscriptionKey key, Consumer<JsonNode> listener);

    /**
     * 调用 ROS 2 Service。
     *
     * @param service     服务名
     * @param messageType 服务类型（可空）
     * @param request     请求内容 JSON
     * @return 以结果 JSON 完成的 Future；异常完成表示调用失败
     */
    CompletableFuture<JsonNode> callService(String service, String messageType, JsonNode request);

    /**
     * 提交 ROS 2 Action Goal。
     *
     * @param action      Action 名
     * @param messageType Action 类型（可空）
     * @param goal        Goal 内容 JSON
     * @param handle      网关侧句柄，适配器通过它上报 feedback 与最终结果
     */
    void sendGoal(String action, String messageType, JsonNode goal, RosGoalHandle handle);

    /**
     * 请求取消上游 Action。真正取消成功后适配器仍通过 {@link RosGoalHandle} 上报终态结果；
     * 若 goal 已结束，该方法应直接返回，不得抛出。
     */
    void cancelGoal(String action, String goalToken);
}
