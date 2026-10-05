package com.example.starter.blend;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 调合服务接口报文定义。体积单位立方米(m3)，硫含量单位mg/kg，蒸气压单位kPa，时间时区Asia/Shanghai。
 */
public final class BlendDtos {

    private BlendDtos() {
    }

    /** 组分切罐量。 */
    public record ComponentAmount(@NotNull Long tankId,
                                  @NotNull @DecimalMin(value = "0.001", message = "切罐量必须为正") BigDecimal volume) {
    }

    /** 配方下达请求，requestId 为幂等键。 */
    public record CreateOrderRequest(@NotBlank String requestId,
                                     @NotBlank String productCode,
                                     @NotNull Long blendTankId,
                                     @NotEmpty List<@Valid ComponentAmount> components) {
    }

    /** 化验提交请求，samplePoint 1为首样、2为次样。 */
    public record SampleRequest(@NotBlank String requestId,
                                @NotNull @Min(1) @Max(2) Integer samplePoint) {
    }

    /** 返调请求，生成新批次并引用原批次。 */
    public record ReblendRequest(@NotBlank String requestId,
                                 @NotEmpty List<@Valid ComponentAmount> components) {
    }

    /** 放行封账请求。 */
    public record ReleaseRequest(@NotBlank String requestId) {
    }

    /** 质量指标实际值（按线性调合模型计算，保留3位小数）。 */
    public record Metrics(BigDecimal octane, BigDecimal sulfur, BigDecimal vaporPressure) {
    }

    /** 调合单下达响应。 */
    public record OrderResponse(long orderId, String orderNo, String status, long batchId,
                                BigDecimal totalVolume, Metrics metrics, List<String> marginHints) {
    }

    /** 化验提交响应；late=true 表示封账后迟到数据，仅记录不改判定。 */
    public record SampleResponse(Long sampleId, Long batchId, int samplePoint, boolean qualified,
                                 Metrics metrics, List<String> marginHints, String orderStatus, boolean late) {
    }

    /** 返调响应。 */
    public record ReblendResponse(long orderId, long batchId, long originBatchId, int seq,
                                  Metrics metrics, List<String> marginHints, String orderStatus) {
    }

    /** 放行封账响应。 */
    public record ReleaseResponse(long orderId, String status, String releasedAt) {
    }

    /** 批次内化验记录视图。 */
    public record SampleView(long sampleId, int samplePoint, boolean qualified,
                             Metrics metrics, List<String> marginHints) {
    }

    /** 批次链节点视图，originBatchId 为返调来源批次，NULL 表示原始批次。 */
    public record BatchView(long batchId, int seq, Long originBatchId, String status, List<SampleView> samples) {
    }

    /** 调合单详情，指标与卡边率由组分快照实时复算，重复查询结果稳定。 */
    public record OrderDetail(long orderId, String orderNo, String productCode, long blendTankId, String status,
                              BigDecimal totalVolume, int reblendCount, Metrics metrics, List<String> marginHints,
                              List<BatchView> batches, List<String> lateEvents) {
    }

    /** 清洗单创建请求；时间窗为半开区间[windowStart, windowEnd)，端点相接不算冲突。 */
    public record CreateCleaningRequest(@NotBlank String requestId,
                                        @NotNull Long blendTankId,
                                        @NotBlank String toProduct,
                                        @NotNull @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                        LocalDateTime windowStart,
                                        @NotNull @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                        LocalDateTime windowEnd) {
    }

    /** 清洗完成确认请求。 */
    public record CompleteCleaningRequest(@NotBlank String requestId) {
    }

    /** 清洗单响应；时间为yyyy-MM-dd HH:mm:ss（Asia/Shanghai），completedAt为null表示未完成。 */
    public record CleaningResponse(long cleaningId, long blendTankId, String fromProduct, String toProduct,
                                   String status, String windowStart, String windowEnd, String completedAt) {
    }

    /** 调合单占用视图节点；occupiedTo为null表示执行中开口区间。 */
    public record OrderOccupancy(long orderId, String orderNo, String productCode,
                                 String occupiedFrom, String occupiedTo) {
    }

    /** 清洗占用视图节点，时间窗为半开区间[windowStart, windowEnd)。 */
    public record CleaningOccupancy(long cleaningId, String fromProduct, String toProduct,
                                    String windowStart, String windowEnd) {
    }

    /** 罐占用视图：罐品种状态、执行中调合单占用与执行中清洗占用窗。 */
    public record TankOccupancyView(long blendTankId, String code, String tankStatus, String currentProduct,
                                    String cleanForProduct, OrderOccupancy orderOccupancy,
                                    CleaningOccupancy cleaningOccupancy) {
    }

    /** 统一错误报文。 */
    public record ErrorBody(String error, String message) {
    }
}
