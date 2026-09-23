package com.example.starter.lot.api;

import com.example.starter.lot.domain.LotStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 转换结果。
 *
 * @param requestId   幂等请求键
 * @param conversionId 转换单 ID
 * @param unit        计量单位
 * @param inputs      实际记账的父批次消费明细
 * @param outputs     实际记账的子批次产出明细
 * @param totalInputQty  总消费量
 * @param totalOutputQty 总产出量
 * @param loss        实际损耗 = 总消费 - 总产出
 * @param allowedLoss 本次允许损耗
 * @param children    新子批次提交后的状态（可能因已先提交的隔离来源而立即被隔离）
 */
public record ConversionResponse(
        String requestId,
        long conversionId,
        String unit,
        List<ConversionRequest.InputItem> inputs,
        List<ConversionRequest.OutputItem> outputs,
        BigDecimal totalInputQty,
        BigDecimal totalOutputQty,
        BigDecimal loss,
        BigDecimal allowedLoss,
        List<ChildStatus> children
) {

    /**
     * 新子批次提交后状态。
     *
     * @param lotId                     子批次 ID
     * @param status                    状态
     * @param activeQuarantineSources   提交时生效的隔离来源（包含先于本转换提交的祖先隔离）
     */
    public record ChildStatus(String lotId, LotStatus status, List<String> activeQuarantineSources) {
    }
}
