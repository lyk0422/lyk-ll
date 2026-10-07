package com.example.starter.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 分车清单创建请求：一车一清单，建立时全量预占并冻结合格头只集合，新车单版本自 1 起。
 *
 * @param requestId  幂等请求标识，全局唯一
 * @param truckNo    合成车号，批次内唯一（含已撤销车单，车号不可复用）
 * @param headNos    本车冻结头只标识集合：1 至 50 个且互不相同，须全部属于本批合格（QUALIFIED）集合
 * @param occurredAt 清单创建业务精确时刻（UTC ISO-8601），参与指纹，作为冻结证据
 */
public record TruckCreateRequest(
        @NotBlank String requestId,
        @NotBlank String truckNo,
        @NotEmpty @Size(min = 1, max = 50, message = "每车头只数须在 1 至 50 个之间")
        List<@NotBlank String> headNos,
        @NotBlank String occurredAt) {
}
