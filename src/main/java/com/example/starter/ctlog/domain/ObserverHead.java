package com.example.starter.ctlog.domain;

import java.time.Instant;

/**
 * 观察者最新已接受的签名树头位置。
 */
public record ObserverHead(String observerId, long treeSize, String rootHash,
                           Instant observedAt, String signature, Instant updatedAt) {
}
