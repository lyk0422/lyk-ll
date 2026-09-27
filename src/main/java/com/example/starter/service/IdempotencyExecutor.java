package com.example.starter.service;

import java.util.function.Supplier;

/**
 * 幂等执行器：以 requestId 为键，指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参返回 409 且不改变工序状态。
 */
public interface IdempotencyExecutor {

    /**
     * 在幂等保护下执行业务动作。
     *
     * @param requestId      幂等请求号
     * @param operation      操作类型
     * @param businessFields 全部业务字段（参与指纹）
     * @param action         首次执行的业务动作，返回状态码与响应体
     * @return 首次结果或重放结果
     */
    Outcome execute(String requestId, String operation, Object businessFields, Supplier<Outcome> action);

    /**
     * 成功才占键的幂等执行：业务成功时业务变更、版本推进与台账同事务提交；
     * 业务失败（含 400/404/422）整体回滚且<b>不登记台账</b>，原 requestId 可改参或原参重试，
     * 失败以携带原 HTTP 状态码的 {@link Outcome} 返回；
     * 已被成功结果占用的键，同参重放首次冻结结果，异参返回 409。
     */
    Outcome executeSuccessOnly(String requestId, String operation, Object businessFields, Supplier<Outcome> action);
}
