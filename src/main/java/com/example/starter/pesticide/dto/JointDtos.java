package com.example.starter.pesticide.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 联检（疑似批次多项目联合检测）API 请求与响应模型。
 * 所有规则均为合成规则，不对应真实检测收费或监管要求。
 */
public final class JointDtos {

    private JointDtos() {
    }

    /** 联检建单请求：引用冻结快检结果与至少两个唯一的合成项目 */
    public record JointCreateRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "批次 ID 不能为空") @Positive(message = "批次 ID 必须为正") Long batchId,
            @NotNull(message = "冻结快检记录 ID 不能为空") @Positive(message = "快检记录 ID 必须为正") Long sourceTestId,
            @NotEmpty(message = "联检项目不能为空") List<@NotBlank(message = "检测项目不能为空") String> items) {
    }

    /** 冻结的联检项目视图（含逐项限值与目录版本） */
    public record JointItemView(
            String item,
            BigDecimal limitValue,
            String catalogVersion) {
    }

    /** 联检建单响应 */
    public record JointCreateResponse(
            Long orderId,
            String requestId,
            Long batchId,
            Long sourceTestId,
            String status,
            Integer version,
            String capabilityVersion,
            List<JointItemView> items,
            Instant createdAt,
            boolean replayed) {
    }

    /** 单项分样分配请求 */
    public record AssignmentItem(
            @NotBlank(message = "检测项目不能为空") String item,
            @NotBlank(message = "样本号不能为空") String sampleNo,
            @NotBlank(message = "机构编码不能为空") String agencyCode) {
    }

    /** 分样请求：须覆盖联检单全部项目，样本号全局唯一，机构具备对应项目能力 */
    public record JointAssignRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "联检单 ID 不能为空") @Positive(message = "联检单 ID 必须为正") Long orderId,
            @NotNull(message = "expectedVersion 不能为空") @Positive(message = "expectedVersion 必须为正") Long expectedVersion,
            @NotEmpty(message = "分样分配不能为空") @Valid List<AssignmentItem> assignments) {
    }

    /** 分样结果视图 */
    public record AssignmentView(
            String item,
            String sampleNo,
            String agencyCode,
            String agencyCapabilityVersion) {
    }

    /** 分样响应 */
    public record JointAssignResponse(
            Long orderId,
            String requestId,
            String status,
            Integer version,
            List<AssignmentView> assignments,
            Instant createdAt,
            boolean replayed) {
    }

    /**
     * 联检结果请求：引用分样样本与项目，浓度有限非负；
     * remeasure=false 为首次结果，true 为复测（替换该项当前结果，旧结果保留并递增版本）。
     */
    public record JointResultRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "联检单 ID 不能为空") @Positive(message = "联检单 ID 必须为正") Long orderId,
            @NotNull(message = "expectedVersion 不能为空") @Positive(message = "expectedVersion 必须为正") Long expectedVersion,
            @NotBlank(message = "检测项目不能为空") String item,
            @NotBlank(message = "样本号不能为空") String sampleNo,
            @NotNull(message = "浓度不能为空")
            @DecimalMin(value = "0", message = "浓度不能为负") BigDecimal concentration,
            @NotNull(message = "检测发生时刻不能为空") Instant occurredAt,
            @NotNull(message = "是否复测不能为空") Boolean remeasure) {
    }

    /** 联检结果响应 */
    public record JointResultResponse(
            Long orderId,
            String requestId,
            String item,
            String sampleNo,
            Integer orderVersion,
            Integer resultVersion,
            BigDecimal concentration,
            Boolean qualified,
            String status,
            Instant occurredAt,
            Instant createdAt,
            boolean replayed,
            boolean supplementary) {
    }

    /** 综合判定请求：expectedVersion 失配则零写入 */
    public record JointDetermineRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "联检单 ID 不能为空") @Positive(message = "联检单 ID 必须为正") Long orderId,
            @NotNull(message = "expectedVersion 不能为空") @Positive(message = "expectedVersion 必须为正") Long expectedVersion) {
    }

    /** 综合判定中单项证据快照 */
    public record DeterminationItemView(
            String item,
            Integer resultVersion,
            BigDecimal concentration,
            Boolean qualified) {
    }

    /** 综合判定响应：携带全部项目结果版本并冻结证据，合格批次放行、不合格转处置留样 */
    public record JointDetermineResponse(
            Long orderId,
            String requestId,
            String overallResult,
            Integer version,
            List<DeterminationItemView> items,
            String batchStatus,
            Instant retentionUntil,
            Instant determinedAt,
            boolean replayed) {
    }

    /** 当前项目结果视图（取最新结果版本） */
    public record JointResultView(
            String item,
            String sampleNo,
            Integer orderVersion,
            Integer resultVersion,
            BigDecimal concentration,
            Boolean qualified,
            Instant occurredAt) {
    }

    /** 联检单详情 */
    public record JointOrderResponse(
            Long orderId,
            Long batchId,
            Long sourceTestId,
            String status,
            Integer version,
            String capabilityVersion,
            String overallResult,
            Instant createdAt,
            Instant updatedAt,
            List<JointItemView> items,
            List<AssignmentView> assignments,
            List<JointResultView> currentResults,
            List<DeterminationItemView> determination) {
    }

    /** 合成项目目录条目 */
    public record CatalogItemView(
            String item,
            BigDecimal limitValue,
            String catalogVersion) {
    }

    /** 合成机构能力视图 */
    public record AgencyView(
            String code,
            String name,
            String capabilityVersion,
            List<String> items) {
    }

    /** 合成目录查询响应（项目目录 + 机构能力，仅合成数据） */
    public record CatalogResponse(
            String capabilityDirectoryVersion,
            List<CatalogItemView> items,
            List<AgencyView> agencies) {
    }
}
