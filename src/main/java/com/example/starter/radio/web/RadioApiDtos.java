package com.example.starter.radio.web;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 放射性药物服务 API 请求/响应载体。时刻字段请求与响应均为 ISO-8601 UTC 字符串。
 */
public final class RadioApiDtos {

    private RadioApiDtos() {
    }

    /** 活度计登记请求。 */
    public record RegisterCalibratorRequest(@NotBlank String code, @NotBlank String name) {
    }

    /** 日检请求：requestId 幂等；偏差绝对值恰 5% 通过，超过 5% 当日禁用。 */
    public record DailyCheckRequest(
            @NotBlank String requestId,
            @NotNull Long calibratorId,
            @NotBlank String checkDate,
            @Positive double referenceActivityBq,
            @Positive double measuredActivityBq) {

        public LocalDate parsedCheckDate() {
            return LocalDate.parse(checkDate);
        }
    }

    /** 母液登记请求。referenceTime 为初始活度标定时刻（ISO-8601 UTC）。 */
    public record RegisterMotherLiquorRequest(
            @NotBlank String batchCode,
            @NotBlank String nuclideCode,
            @Positive double initialActivityBq,
            @NotBlank String referenceTime) {

        public long referenceTimeEpoch() {
            return Instant.parse(referenceTime).getEpochSecond();
        }
    }

    /** 处方登记请求。administrationTime 为给药时刻（ISO-8601 UTC）。 */
    public record RegisterPrescriptionRequest(
            @NotBlank String code,
            @NotBlank String nuclideCode,
            @Positive double prescribedActivityBq,
            @NotBlank String administrationTime) {

        public long administrationTimeEpoch() {
            return Instant.parse(administrationTime).getEpochSecond();
        }
    }

    /** 分装计算请求：requestId 幂等；与处方偏差恰 10% 合法，超过 10% 拒绝。 */
    public record CalculateRequest(
            @NotBlank String requestId,
            @NotNull Long motherLiquorId,
            @NotNull Long prescriptionId,
            @NotNull Long calibratorId) {
    }

    /** 双人核对请求。 */
    public record CheckRequest(@NotBlank String checker) {
    }

    /** 发药请求：requestId 幂等；measuredAt 为剩余活度测量时刻（ISO-8601 UTC）。 */
    public record DispenseRequest(
            @NotBlank String requestId,
            @NotNull Long dispensingId,
            @PositiveOrZero double remainingActivityBq,
            @NotBlank String measuredAt) {

        public long measuredAtEpoch() {
            return Instant.parse(measuredAt).getEpochSecond();
        }
    }

    /** 迟到测量数据请求。 */
    public record MeasurementRequest(@NotBlank String payload) {
    }

    /**
     * 给药时刻变更候选提交请求：requestId 幂等；expectedVersion 须等于当前版本（从 1 开始）；
     * newAdministrationTime 为候选新给药时刻（ISO-8601 UTC），须晚于母液参考时刻。
     */
    public record ChangeSubmitRequest(
            @NotBlank String requestId,
            @NotNull Long dispensingId,
            @NotNull Long expectedVersion,
            @NotBlank String newAdministrationTime) {

        public long newAdministrationTimeEpoch() {
            return Instant.parse(newAdministrationTime).getEpochSecond();
        }
    }

    /** 变更候选双人复核请求（两名不同人员）。 */
    public record ChangeReviewRequest(@NotBlank String checker) {
    }
}
