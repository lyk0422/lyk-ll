package com.example.starter.rosbridge.gateway;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一个共享上游订阅的运行时：ROS 订阅句柄 + 当前在线成员 clientId 集合。
 * 成员操作在调用方（网关）保证的串行订阅流程下进行，集合本身加锁以支持并发扇出遍历。
 */
public class SharedRuntime {

    private final String topic;
    private final String type;
    private final String qosKey;
    private final AutoCloseable upstream;
    private final Set<String> clients = new LinkedHashSet<>();

    public SharedRuntime(String topic, String type, String qosKey, AutoCloseable upstream) {
        this.topic = topic;
        this.type = type;
        this.qosKey = qosKey;
        this.upstream = upstream;
    }

    public String topic() {
        return topic;
    }

    public String type() {
        return type;
    }

    public String qosKey() {
        return qosKey;
    }

    public AutoCloseable upstream() {
        return upstream;
    }

    public synchronized void attach(String clientId) {
        clients.add(clientId);
    }

    public synchronized void detach(String clientId) {
        clients.remove(clientId);
    }

    public synchronized Set<String> onlineClients() {
        return new LinkedHashSet<>(clients);
    }
}
