package com.example.starter.ride.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * API 请求与响应 DTO 集合。所有写操作请求均携带 requestId 用于幂等；
 * 受检设备相关新增写操作额外携带 expectedVersion（版本从1开始，成功变更加1）。
 */
public final class RideDtos {

    private RideDtos() {
    }

    /** 日检/复检/监督检验通用清单项。 */
    public record CheckItem(
            @NotBlank(message = "检查项名称不能为空") String itemName,
            @NotNull(message = "检查项合格标记不能为空") Boolean passed) {
    }

    /**
     * 创建设备请求（合成数据）。
     * inspectionRequired 为合成受检标志，缺省 FALSE 表示旧设备，行为不变；
     * cycleVersion 为合成检验周期版本，缺省1，不引用真实法定周期。
     */
    public record CreateRideRequest(
            @NotBlank(message = "设备名称不能为空") String name,
            @Positive(message = "载荷阈值必须为正数") int maxLoad,
            @Positive(message = "速度阈值必须为正数") int maxSpeed,
            Boolean inspectionRequired,
            @Positive(message = "检验周期版本必须为正数") Integer cycleVersion) {

        /** 旧入口兼容：未启用受检标志的旧设备行为不变，检验周期版本缺省1。 */
        public CreateRideRequest(String name, int maxLoad, int maxSpeed) {
            this(name, maxLoad, maxSpeed, null, null);
        }
    }

    /** 日检请求：清单逐项合格方可试运行。 */
    public record DailyCheckRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotEmpty(message = "日检清单不能为空") List<@Valid CheckItem> items) {
    }

    /** 试运行请求：normal=true 表示空载无异常。 */
    public record TrialRunRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotNull(message = "试运行结果不能为空") Boolean normal) {
    }

    /** 开放请求。 */
    public record OpenRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId) {
    }

    /** 安全装置失效上报：压杠、安全带与联锁装置任一失效整机立即停运。 */
    public record SafetyFaultRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotBlank(message = "安全装置名称不能为空") String device) {
    }

    /** 复检请求：故障停机后复检合格方可复开。 */
    public record RecheckRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotEmpty(message = "复检清单不能为空") List<@Valid CheckItem> items) {
    }

    /** 复开请求：同日复开最多2次。 */
    public record ReopenRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId) {
    }

    /** 闭园归档请求：归档为终态，重复归档返回幂等成功。 */
    public record ArchiveRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId) {
    }

    /** 运行监测读数（合成数据）。 */
    public record ReadingRequest(
            @NotNull(message = "载荷读数不能为空") Integer loadValue,
            @NotNull(message = "速度读数不能为空") Integer speedValue) {
    }

    /**
     * 年度证书首次发证请求（合成规则）。
     * issuedAt 为检验合格时刻（UTC 精确时刻）；
     * expiresAt 缺省为合格时刻加365×24小时，显式给出时必须与默认值精确相等。
     */
    public record IssueCertificateRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotNull(message = "expectedVersion不能为空") Long expectedVersion,
            @NotNull(message = "检验合格时刻issuedAt不能为空") Instant issuedAt,
            Instant expiresAt) {
    }

    /**
     * 换证请求：原子切换唯一当前指针，旧证书置为历史（SUPERSEDED）不可覆盖。
     */
    public record RenewCertificateRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotNull(message = "expectedVersion不能为空") Long expectedVersion,
            @NotNull(message = "检验合格时刻issuedAt不能为空") Instant issuedAt,
            Instant expiresAt) {
    }

    /** 撤销请求：撤销当前证书并清空当前指针，撤销时刻为 UTC 精确时刻。 */
    public record RevokeCertificateRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotNull(message = "expectedVersion不能为空") Long expectedVersion,
            @NotNull(message = "撤销时刻revokedAt不能为空") Instant revokedAt) {
    }

    /** 登记大修请求：原子递增大修版本并暂停开放。 */
    public record RegisterOverhaulRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotNull(message = "expectedVersion不能为空") Long expectedVersion) {
    }

    /**
     * 大修后监督检验请求：须绑定当前大修版本，两名不同检验人共同确认全清单合格。
     */
    public record SupervisoryInspectionRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotNull(message = "expectedVersion不能为空") Long expectedVersion,
            @NotBlank(message = "检验人A不能为空") @Size(max = 64) String inspectorA,
            @NotBlank(message = "检验人B不能为空") @Size(max = 64) String inspectorB,
            @NotEmpty(message = "监督检验清单不能为空") List<@Valid CheckItem> items) {
    }

    /** 统一错误响应体。 */
    public record ErrorBody(String error, String message) {
    }
}
