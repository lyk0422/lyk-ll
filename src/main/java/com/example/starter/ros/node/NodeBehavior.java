package com.example.starter.ros.node;

/**
 * 模拟节点的行为配置。
 *
 * @param mode            ACCEPT / REJECT / TIMEOUT
 * @param reason          REJECT 时返回给上层的原因
 * @param validateDelayMs 校验耗时（毫秒），用于模拟接近或超过时限
 */
public record NodeBehavior(Mode mode, String reason, long validateDelayMs) {

    public enum Mode {
        ACCEPT, REJECT, TIMEOUT
    }

    public static NodeBehavior accept() {
        return new NodeBehavior(Mode.ACCEPT, null, 0);
    }
}
