package com.example.starter.ros;

/**
 * 节点适配器拒绝候选配置时抛出；携带拒绝理由。
 */
public class NodeRejectedException extends RuntimeException {

    public NodeRejectedException(String message) {
        super(message);
    }
}
