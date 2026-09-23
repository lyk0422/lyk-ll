package com.example.starter.ros;

/**
 * 节点适配器在配置时限内无响应时抛出。
 */
public class NodeTimeoutException extends RuntimeException {

    public NodeTimeoutException(String message) {
        super(message);
    }
}
