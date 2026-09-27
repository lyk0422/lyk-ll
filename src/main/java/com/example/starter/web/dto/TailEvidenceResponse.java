package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 列尾装置证据查询响应：装置视图、全部绑定历史（含已解除）及每条绑定下的一致性确认。
 *
 * @param device   装置当前视图
 * @param bindings 绑定历史（按绑定ID升序，全部保留）
 */
public record TailEvidenceResponse(
        TailDeviceResponse device,
        List<BindingEvidence> bindings) {

    /**
     * 单条绑定证据。
     *
     * @param bindingId       绑定ID
     * @param jobId           受控作业ID
     * @param tailCarNo       冻结尾车号
     * @param consistVersion  冻结编组版本
     * @param boundAt         绑定时刻（UTC）
     * @param releasedAt      解除时刻（UTC）；NULL 生效中
     * @param releaseType     解除类型 UNBIND/CLOSURE；NULL 生效中
     * @param active          是否生效中
     * @param confirmations   该绑定下全部一致性确认（含已失效）
     */
    public record BindingEvidence(
            long bindingId,
            long jobId,
            String tailCarNo,
            int consistVersion,
            Instant boundAt,
            Instant releasedAt,
            String releaseType,
            boolean active,
            List<ConfirmationEvidence> confirmations) {
    }

    /**
     * 单次一致性共同确认证据。
     *
     * @param confirmationId    确认ID
     * @param tailCarNo         确认尾车号
     * @param consistVersion    确认编组版本
     * @param confirmer1        第一名确认人员
     * @param confirmer2        第二名确认人员
     * @param confirmedAt       确认时刻（UTC）
     * @param valid             是否仍有效
     * @param invalidatedAt     失效时刻（UTC）；NULL 仍有效
     * @param invalidatedReason 失效原因；NULL 仍有效
     */
    public record ConfirmationEvidence(
            long confirmationId,
            String tailCarNo,
            int consistVersion,
            String confirmer1,
            String confirmer2,
            Instant confirmedAt,
            boolean valid,
            Instant invalidatedAt,
            String invalidatedReason) {
    }
}
