package com.example.starter.blend;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 调合服务 REST 契约：请求与响应 DTO。所有数量单位为 kg，指标单位见 {@link BlendModel}。
 */
public final class BlendContracts {

    private BlendContracts() {
    }

    /** 组分切罐量：tankCode 组分罐号，qtyKg 切罐量（kg，正数）。 */
    public record ComponentQty(
            @NotBlank String tankCode,
            @NotNull @Positive BigDecimal qtyKg) {
    }

    /** 调合单下达请求：requestId 为幂等键，指纹覆盖全部业务字段。 */
    public record CreateOrderRequest(
            @NotBlank String requestId,
            @NotBlank String blendTankCode,
            @NotBlank String productCode,
            @NotNull @Positive BigDecimal targetQtyKg,
            @NotEmpty List<@Valid ComponentQty> components) {
    }

    /** 化验提交请求：samplePoint 取值 1（首样）/ 2（复样）。 */
    public record LabSampleRequest(
            @NotBlank String requestId,
            @NotNull @Min(1) @Max(2) Integer samplePoint) {
    }

    /** 返调请求：追加组分，生成引用原批次的新调合批次。 */
    public record ReblendRequest(
            @NotBlank String requestId,
            @NotEmpty List<@Valid ComponentQty> additionalComponents) {
    }

    /** 调合单响应：含合成指标快照与卡边提示。 */
    public record OrderResponse(
            long orderId,
            String requestId,
            String status,
            String blendTankCode,
            String productCode,
            BigDecimal targetQtyKg,
            long activeBatchId,
            BlendModel.IndicatorSet indicators,
            List<BlendModel.EdgeHint> edgeHints,
            boolean replayed) {
    }

    /** 化验样本响应：late=TRUE 表示封账后迟到数据，仅记录不改判定。 */
    public record LabSampleResponse(
            long sampleId,
            String requestId,
            long orderId,
            long batchId,
            int samplePoint,
            BlendModel.IndicatorSet indicators,
            boolean passed,
            boolean late,
            String orderStatus,
            String batchStatus,
            List<BlendModel.EdgeHint> edgeHints,
            boolean replayed) {
    }

    /** 返调响应：新批次引用原批次，指标为累计调合量的复算结果。 */
    public record ReblendResponse(
            long orderId,
            long newBatchId,
            int batchNo,
            long parentBatchId,
            String orderStatus,
            BlendModel.IndicatorSet indicators,
            List<BlendModel.EdgeHint> edgeHints,
            boolean replayed) {
    }

    /** 封账响应：重复封账返回幂等成功。 */
    public record ReleaseResponse(
            long orderId,
            String status,
            Instant releasedAt,
            boolean replayed) {
    }

    /** 批次链视图。 */
    public record BatchView(
            long batchId,
            int batchNo,
            Long parentBatchId,
            String status,
            BigDecimal totalQtyKg,
            BlendModel.IndicatorSet indicators) {
    }

    /** 化验样本视图。 */
    public record SampleView(
            long sampleId,
            long batchId,
            int samplePoint,
            BlendModel.IndicatorSet indicators,
            boolean passed,
            boolean late) {
    }

    /** 切罐流水视图。 */
    public record MovementView(
            long batchId,
            String tankCode,
            BigDecimal qtyKg) {
    }

    /** 调合单详情：指标、卡边率与批次链均可复算，重复查询结果稳定。 */
    public record OrderDetailResponse(
            long orderId,
            String requestId,
            String status,
            String blendTankCode,
            String productCode,
            BigDecimal targetQtyKg,
            Instant createdAt,
            Instant releasedAt,
            BlendModel.IndicatorSet indicators,
            List<BlendModel.EdgeHint> edgeHints,
            List<BatchView> batches,
            List<SampleView> samples,
            List<MovementView> movements) {
    }
}
