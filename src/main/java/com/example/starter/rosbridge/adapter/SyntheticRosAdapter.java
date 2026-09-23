package com.example.starter.rosbridge.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地合成 ROS 适配器：不连接真实 ROS，用定时器模拟 Topic 心跳、
 * Service 回声以及 Action 执行过程，供本地启动与自动化测试使用。
 *
 * <p>同时保留测试钩子：{@link #emit(String, JsonNode)} 可向某 Topic 的全部
 * 上游订阅注入消息；{@link #stubService} / {@link #stubAction} 可定制行为。
 */
public class SyntheticRosAdapter implements RosAdapter {

    /** 合成 Topic 心跳内容字段。 */
    public static final String TOPIC_TYPE = "std_msgs/msg/String";

    private static final AtomicInteger ID_GEN = new AtomicInteger();

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;

    /** topic 名称 -> 该 Topic 上的上游订阅监听器集合。 */
    private final Map<String, List<TopicListener>> topicListeners = new ConcurrentHashMap<>();
    /** 自动心跳调度的 topic 集合，避免重复调度。 */
    private final Map<String, Boolean> heartbeats = new ConcurrentHashMap<>();

    private final Map<String, ServiceBehavior> serviceBehaviors = new ConcurrentHashMap<>();
    private final Map<String, ActionBehavior> actionBehaviors = new ConcurrentHashMap<>();

    private volatile long serviceLatencyMs = 20;
    private volatile long actionStepMs = 20;

    public SyntheticRosAdapter() {
        this(new ObjectMapper());
    }

    public SyntheticRosAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "synthetic-ros-" + ID_GEN.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 配置 Service 模拟行为；未配置时默认回声 args 并在很短延迟后成功。
     */
    public void stubService(String service, ServiceBehavior behavior) {
        serviceBehaviors.put(service, behavior);
    }

    /**
     * 配置 Action 模拟行为；未配置时默认产生 1 次 feedback 后成功。
     */
    public void stubAction(String action, ActionBehavior behavior) {
        actionBehaviors.put(action, behavior);
    }

    /** 设置默认 Service 调用延迟，测试可置 0。 */
    public void setServiceLatencyMs(long serviceLatencyMs) {
        this.serviceLatencyMs = serviceLatencyMs;
    }

    /** 设置默认 Action 每步延迟，测试可置 0。 */
    public void setActionStepMs(long actionStepMs) {
        this.actionStepMs = actionStepMs;
    }

    /**
     * 向指定 Topic 的全部上游订阅注入一条消息（测试钩子）。
     *
     * @return 实际投递到的上游订阅数量
     */
    public int emit(String topic, JsonNode message) {
        List<TopicListener> listeners = topicListeners.get(topic);
        if (listeners == null) {
            return 0;
        }
        for (TopicListener l : listeners) {
            l.onMessage(message);
        }
        return listeners.size();
    }

    /**
     * 为 Topic 开启 1Hz 合成心跳（本地演示用），重复调用幂等。
     */
    public void enableHeartbeat(String topic) {
        if (heartbeats.putIfAbsent(topic, Boolean.TRUE) != null) {
            return;
        }
        AtomicInteger seq = new AtomicInteger();
        scheduler.scheduleAtFixedRate(() -> {
            ObjectNode node = mapper.createObjectNode();
            node.put("data", "synthetic-" + topic + "-" + seq.incrementAndGet());
            emit(topic, node);
        }, 1, 1, TimeUnit.SECONDS);
    }

    /** 当前某 Topic 的上游订阅数（验证引用计数用）。 */
    public int upstreamSubscriptionCount(String topic) {
        List<TopicListener> listeners = topicListeners.get(topic);
        return listeners == null ? 0 : listeners.size();
    }

    /** Spring 容器关闭时释放内部调度线程（线程为守护线程，这里显式关闭避免测试泄漏）。 */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    @Override
    public TopicHandle subscribeTopic(String topic, String type, Map<String, Object> qos, TopicListener listener) {
        List<TopicListener> listeners =
                topicListeners.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                listeners.remove(listener);
            }
        };
    }

    @Override
    public void callService(String service, JsonNode args, ServiceCallback callback) {
        ServiceBehavior behavior = serviceBehaviors.get(service);
        long delay = serviceLatencyMs;
        if (behavior != null) {
            behavior.invoke(service, args, callback, scheduler, delay);
        } else {
            scheduler.schedule(() -> callback.onSuccess(echoResult(args)), delay, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public GoalHandle sendGoal(String action, JsonNode goal, GoalListener listener) {
        AtomicBoolean canceled = new AtomicBoolean();
        ActionBehavior behavior = actionBehaviors.get(action);
        if (behavior != null) {
            behavior.run(action, goal, listener, scheduler, actionStepMs, canceled);
        } else {
            scheduler.schedule(() -> {
                if (canceled.get()) {
                    listener.onResult(new ActionOutcome("CANCELED", mapper.createObjectNode()));
                    return;
                }
                ObjectNode fb = mapper.createObjectNode();
                fb.put("progress", 50);
                listener.onFeedback(fb);
                scheduler.schedule(() -> {
                    if (canceled.get()) {
                        listener.onResult(new ActionOutcome("CANCELED", mapper.createObjectNode()));
                    } else {
                        listener.onResult(new ActionOutcome("OK", echoResult(goal)));
                    }
                }, Math.max(actionStepMs, 1), TimeUnit.MILLISECONDS);
            }, Math.max(actionStepMs, 1), TimeUnit.MILLISECONDS);
        }
        return () -> canceled.set(true);
    }

    private JsonNode echoResult(JsonNode args) {
        ObjectNode node = mapper.createObjectNode();
        node.set("echo", args == null ? mapper.createObjectNode() : args);
        return node;
    }

    /** 可定制的 Service 行为。 */
    @FunctionalInterface
    public interface ServiceBehavior {
        void invoke(String service, JsonNode args, ServiceCallback callback,
                    ScheduledExecutorService scheduler, long defaultDelayMs);
    }

    /** 可定制的 Action 行为。 */
    @FunctionalInterface
    public interface ActionBehavior {
        void run(String action, JsonNode goal, GoalListener listener,
                 ScheduledExecutorService scheduler, long stepMs, AtomicBoolean canceled);
    }
}
