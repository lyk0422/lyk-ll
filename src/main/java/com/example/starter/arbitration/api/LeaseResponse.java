package com.example.starter.arbitration.api;

/**
 * 租约操作结果。
 *
 * @param granted      是否授权/当前有效
 * @param controllerId 持有方控制器标识
 * @param requestId    幂等键
 * @param resources    涉及的资源名
 * @param priority     优先级
 * @param acquiredAtMs 授权时间（UTC epoch 毫秒）
 * @param expiresAtMs  到期时间（UTC epoch 毫秒）
 * @param preempted    本次申请是否通过抢占获得；被抢占的资源列表为空表示未发生抢占
 * @param preemptedFromResources 发生抢占的资源名
 * @param idempotentReplay 是否为同参重复请求的幂等回放
 */
public record LeaseResponse(
        boolean granted,
        String controllerId,
        String requestId,
        java.util.List<String> resources,
        int priority,
        long acquiredAtMs,
        long expiresAtMs,
        boolean preempted,
        java.util.List<String> preemptedFromResources,
        boolean idempotentReplay) {
}
