package com.example.starter.web.dto;

import java.util.List;

/**
 * 列尾绑定证据查询视图：全部绑定历史（含已解除）及各自的一致性确认记录。
 *
 * @param jobId    作业ID
 * @param bindings 绑定历史，按绑定时间排序
 */
public record TailBindingHistoryResponse(
        long jobId,
        List<BindingView> bindings) {

    /**
     * 单条绑定及其确认记录。
     *
     * @param bindingId      绑定主键
     * @param deviceNo       装置编号
     * @param tailCarNo      绑定冻结的尾车车辆号
     * @param consistVersion 绑定冻结的编组版本
     * @param status         绑定状态：ACTIVE / RELEASED
     * @param releaseReason  解除原因；null 生效中
     * @param confirmations  该绑定下的一致性确认记录
     */
    public record BindingView(
            long bindingId,
            String deviceNo,
            String tailCarNo,
            int consistVersion,
            String status,
            String releaseReason,
            List<TailConfirmationResponse> confirmations) {
    }
}
