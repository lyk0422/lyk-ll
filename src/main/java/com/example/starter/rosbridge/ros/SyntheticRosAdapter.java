package com.example.starter.rosbridge.ros;

import com.example.starter.rosbridge.config.RosBridgeProperties;
import com.example.starter.rosbridge.protocol.ProtocolJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 合成 ROS 适配器：不依赖真实 ROS，提供可预测、可测试的 Topic/Service/Action 行为。
 * <ul>
 *   <li>Topic：测试/演示可调用 {@link #publish} 手动推送；订阅 {@code /synthetic/tick}
 *       且配置了 tick 周期时自动周期发布。</li>
 *   <li>Service：{@code /echo} 原样回显 args；{@code /slow_service} 永不完成（用于超时验证）；
 *       其余服务回显并附带 service 名。</li>
 *   <li>Action：{@code /slow_action} 只发 feedback 不结束（用于取消验证）；其余 action
 *       发送一条 feedback 后成功；{@code cancelGoal} 触发取消终态。</li>
 * </ul>
 */
@Component
public class SyntheticRosAdapter implements RosAdapter {

    private static final Logger log = LoggerFactory.getLogger(SyntheticRosAdapter.class);
    public static final String SLOW_SERVICE = "/slow_service";
    public static final String SLOW_ACTION = "/slow_action";
    public static final String TICK_TOPIC = "/synthetic/tick";

    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "synthetic-ros");
                t.setDaemon(true);
                return t;
            });
    private final Map<SubscriptionKey, Consumer<JsonNode>> listeners = new ConcurrentHashMap<>();
    private final Map<String, RosGoalHandle> activeGoals = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> goalTasks = new ConcurrentHashMap<>();
    private final Map<SubscriptionKey, ScheduledFuture<?>> tickTasks = new ConcurrentHashMap<>();
    private final AtomicLong tickSeq = new AtomicLong();

    private final RosBridgeProperties properties;

    public SyntheticRosAdapter(RosBridgeProperties properties) {
        this.properties = properties;
    }

    @Override
    public RosSubscription subscribe(SubscriptionKey key, Consumer<JsonNode> listener) {
        listeners.put(key, listener);
        log.info("synthetic upstream subscribed: {}", key);
        ScheduledFuture<?> tickTask = null;
        if (TICK_TOPIC.equals(key.topic()) && properties.syntheticTickIntervalMs() > 0) {
            long interval = properties.syntheticTickIntervalMs();
            tickTask = executor.scheduleAtFixedRate(() -> {
                ObjectNode msg = ProtocolJson.MAPPER.createObjectNode();
                msg.put("seq", tickSeq.incrementAndGet());
                msg.put("data", "tick-" + tickSeq.get());
                listener.accept(msg);
            }, interval, interval, TimeUnit.MILLISECONDS);
            ScheduledFuture<?> existing = tickTasks.put(key, tickTask);
            if (existing != null) {
                existing.cancel(false);
            }
        }
        return () -> {
            listeners.remove(key);
            ScheduledFuture<?> t = tickTasks.remove(key);
            if (t != null) {
                t.cancel(false);
            }
            log.info("synthetic upstream released: {}", key);
        };
    }

    /** 测试/演示：向某个已建立的上游订阅推送一条消息。 */
    public boolean publish(SubscriptionKey key, JsonNode data) {
        Consumer<JsonNode> listener = listeners.get(key);
        if (listener == null) {
            return false;
        }
        listener.accept(data);
        return true;
    }

    /** 当前已建立的上游订阅数（断言引用计数释放用）。 */
    public int activeSubscriptionCount() {
        return listeners.size();
    }

    @Override
    public CompletableFuture<JsonNode> callService(String service, String messageType, JsonNode request) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        if (SLOW_SERVICE.equals(service)) {
            // 永不完成，由网关超时兜底
            return future;
        }
        executor.schedule(() -> {
            ObjectNode result = ProtocolJson.MAPPER.createObjectNode();
            result.put("service", service);
            result.set("echo", request == null ? ProtocolJson.MAPPER.createObjectNode() : request);
            future.complete(result);
        }, 5, TimeUnit.MILLISECONDS);
        return future;
    }

    @Override
    public void sendGoal(String action, String messageType, JsonNode goal, RosGoalHandle handle) {
        activeGoals.put(handle.goalToken(), handle);
        if (SLOW_ACTION.equals(action)) {
            // 周期 feedback、不产生终态，等待取消
            ScheduledFuture<?> task = executor.scheduleAtFixedRate(() -> {
                ObjectNode fb = ProtocolJson.MAPPER.createObjectNode();
                fb.put("progress", 0);
                handle.publishFeedback(fb);
            }, 10, 50, TimeUnit.MILLISECONDS);
            goalTasks.put(handle.goalToken(), task);
            return;
        }
        ScheduledFuture<?> task = executor.schedule(() -> {
            ObjectNode fb = ProtocolJson.MAPPER.createObjectNode();
            fb.put("progress", 50);
            handle.publishFeedback(fb);
            ObjectNode result = ProtocolJson.MAPPER.createObjectNode();
            result.put("ok", true);
            result.set("echo", goal == null ? ProtocolJson.MAPPER.createObjectNode() : goal);
            handle.succeed(result);
            activeGoals.remove(handle.goalToken());
        }, 10, TimeUnit.MILLISECONDS);
        goalTasks.put(handle.goalToken(), task);
    }

    @Override
    public void cancelGoal(String action, String goalToken) {
        ScheduledFuture<?> task = goalTasks.remove(goalToken);
        if (task != null) {
            task.cancel(false);
        }
        RosGoalHandle handle = activeGoals.remove(goalToken);
        if (handle != null) {
            ObjectNode result = ProtocolJson.MAPPER.createObjectNode();
            result.put("cancelled", true);
            handle.cancelled(result);
        }
    }
}
