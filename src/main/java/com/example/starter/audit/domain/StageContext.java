package com.example.starter.audit.domain;

import com.example.starter.audit.model.Bindings;
import com.example.starter.audit.model.Stage;

/**
 * 阶段执行上下文，向可注入的阶段执行器提供绑定版本、上游产物摘要与模拟参数。
 *
 * @param metricsProfile 指标口径（default/extended），用于制造可验证的口径差异
 * @param faultPlan      合成故障注入计划（fail:&lt;阶段&gt;/interrupt:&lt;阶段&gt;），仅首批生效
 */
public record StageContext(
        long taskId,
        Stage stage,
        Bindings bindings,
        String upstreamDigest,
        int attempt,
        String metricsProfile,
        String faultPlan
) {
}
