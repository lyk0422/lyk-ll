package com.example.starter.rosbridge.ros;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 合成 ROS 适配器：不连接真实 ROS，供本地启动与测试使用。
 *
 * <p>能力：</p>
 * <ul>
 *   <li>{@link #emit(topic, JsonNode)}：向某 topic 的所有上游订阅者注入一条消息；</li>
 *   <li>Service：可配置延迟（默认立即成功回显 args），用 {@link #deferService()} 挂起，
 *       再由 {@link #completeDeferredService(int, ServiceOutcome)} 控制完成，便于构造迟到结果；</li>
 *   <li>Action：可由测试注入 feedback 与最终结果，取消时记录取消次数。</li>
 * </ul>
 */
@Component
public class SyntheticRosAdapter implements RosAdapter {

    /** 一个上游 topic 订阅。 */
    public final class Subscription {
        private final String topic;
        private final String type;
        private final Qos qos;
        private final Consumer<JsonNode> sink;
        private volatile boolean closed;

        private Subscription(String topic, String type, Qos qos, Consumer<JsonNode> sink) {
            this.topic = topic;
            this.type = type;
            this.qos = qos;
            this.sink = sink;
        }

        public String topic() {
            return topic;
        }

        public String type() {
            return type;
        }

        public Qos qos() {
            return qos;
        }

        public boolean closed() {
            return closed;
        }

        void close() {
            closed = true;
        }
    }

    /** 挂起的 Service 调用。 */
    public final class PendingService {
        private final String service;
        private final JsonNode args;
        private final Consumer<ServiceOutcome> callback;
        private volatile boolean done;

        private PendingService(String service, JsonNode args, Consumer<ServiceOutcome> callback) {
            this.service = service;
            this.args = args;
            this.callback = callback;
        }

        public String service() {
            return service;
        }

        public JsonNode args() {
            return args;
        }

        public void complete(ServiceOutcome outcome) {
            if (!done) {
                done = true;
                callback.accept(outcome);
            }
        }
    }

    /** 挂起的 Action goal。 */
    public final class PendingGoal implements GoalHandle {
        private final String action;
        private final JsonNode goal;
        private final Consumer<JsonNode> feedbackSink;
        private final Consumer<GoalOutcome> resultSink;
        private volatile boolean canceled;
        private volatile boolean closed;

        private PendingGoal(String action, JsonNode goal,
                            Consumer<JsonNode> feedbackSink, Consumer<GoalOutcome> resultSink) {
            this.action = action;
            this.goal = goal;
            this.feedbackSink = feedbackSink;
            this.resultSink = resultSink;
        }

        public String action() {
            return action;
        }

        public JsonNode goal() {
            return goal;
        }

        public boolean canceled() {
            return canceled;
        }

        public void feedback(JsonNode f) {
            if (!closed && !canceled) {
                feedbackSink.accept(f);
            }
        }

        public void succeed(JsonNode result) {
            if (!closed) {
                closed = true;
                resultSink.accept(GoalOutcome.succeeded(result));
            }
        }

        public void abort(String error) {
            if (!closed) {
                closed = true;
                resultSink.accept(GoalOutcome.aborted(error));
            }
        }

        @Override
        public void cancel() {
            canceled = true;
        }
    }

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final List<PendingService> pendingServices = new CopyOnWriteArrayList<>();
    private final List<PendingGoal> pendingGoals = new CopyOnWriteArrayList<>();

    /** true 时 Service 调用挂起等待外部完成；false 时立即回显成功。 */
    private volatile boolean deferServices = false;

    public void setDeferServices(boolean deferServices) {
        this.deferServices = deferServices;
    }

    @Override
    public AutoCloseable subscribe(String topic, String type, Qos qos,
                                   Consumer<JsonNode> messageSink) {
        Subscription sub = new Subscription(topic, type, qos, messageSink);
        subscriptions.add(sub);
        return () -> {
            sub.close();
            subscriptions.remove(sub);
        };
    }

    @Override
    public void callService(String service, String type, JsonNode args,
                            Consumer<ServiceOutcome> callback) {
        if (deferServices) {
            pendingServices.add(new PendingService(service, args, callback));
        } else {
            callback.accept(ServiceOutcome.success(
                    args == null ? TextNode.valueOf("") : args));
        }
    }

    @Override
    public GoalHandle sendGoal(String action, String type, JsonNode goal,
                               Consumer<JsonNode> feedbackSink, Consumer<GoalOutcome> resultSink) {
        PendingGoal pg = new PendingGoal(action, goal, feedbackSink, resultSink);
        pendingGoals.add(pg);
        return pg;
    }

    /** 向某 topic 的全部活跃上游订阅注入消息，返回实际投递的订阅数。 */
    public int emit(String topic, JsonNode payload) {
        int n = 0;
        for (Subscription s : subscriptions) {
            if (!s.closed && s.topic.equals(topic)) {
                s.sink.accept(payload);
                n++;
            }
        }
        return n;
    }

    public List<Subscription> activeSubscriptions() {
        return List.copyOf(subscriptions);
    }

    public int activeSubscriptionCount(String topic) {
        int n = 0;
        for (Subscription s : subscriptions) {
            if (!s.closed && s.topic.equals(topic)) {
                n++;
            }
        }
        return n;
    }

    public List<PendingService> deferredServices() {
        return List.copyOf(pendingServices);
    }

    /** 完成第 index 个挂起的 Service 调用（按挂起顺序）。 */
    public void completeDeferredService(int index, ServiceOutcome outcome) {
        PendingService ps = pendingServices.get(index);
        ps.complete(outcome);
        pendingServices.remove(ps);
    }

    public List<PendingGoal> pendingGoals() {
        return List.copyOf(pendingGoals);
    }

    public PendingGoal goal(int index) {
        return pendingGoals.get(index);
    }

    /** 测试清理：清空挂起记录（不影响订阅）。 */
    public void reset() {
        pendingServices.clear();
        pendingGoals.clear();
        deferServices = false;
    }
}
