package com.example.starter.web;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.QuotaEvent;
import com.example.starter.domain.UnsealPermit;
import com.example.starter.domain.ValveSeal;
import com.example.starter.service.SealService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 平衡阀铅封、临时解封与复封闭环入口。
 */
@RestController
@RequestMapping("/api")
public class SealController {

    private final SealService sealService;

    public SealController(SealService sealService) {
        this.sealService = sealService;
    }

    /**
     * 铅封登记请求。
     *
     * @param requestId       客户端幂等键
     * @param unitId          目标单元 ID
     * @param sealCode        铅封号，全局唯一
     * @param expectedVersion 期望的当前阀门开度版本（无变更为 0，首次调节后为 1）
     */
    public record RegisterSealRequest(@NotBlank String requestId, @NotNull Long unitId,
                                      @NotBlank String sealCode, @NotNull Integer expectedVersion) {
    }

    /**
     * 解封批准请求。
     *
     * @param requestId       客户端幂等键
     * @param sealCode        引用的铅封号
     * @param expectedVersion 铅封时引用的阀门开度版本
     * @param approver1       第一名授权人
     * @param approver2       第二名授权人，须与第一名不同
     * @param windowStart     有效窗口起点（UTC，含）
     * @param windowEnd       有效窗口终点（UTC，不含）
     */
    public record ApproveUnsealRequest(@NotBlank String requestId, @NotBlank String sealCode,
                                       @NotNull Integer expectedVersion,
                                       @NotBlank String approver1, @NotBlank String approver2,
                                       @NotNull Instant windowStart, @NotNull Instant windowEnd) {
    }

    /**
     * 解封撤销请求。
     *
     * @param requestId       客户端幂等键
     * @param permitId        解封单 ID
     * @param expectedVersion 期望的解封单版本
     * @param revokedBy       撤销操作授权人
     */
    public record RevokeUnsealRequest(@NotBlank String requestId, @NotNull Long permitId,
                                      @NotNull Integer expectedVersion, @NotBlank String revokedBy) {
    }

    /**
     * 复封请求。
     *
     * @param requestId       客户端幂等键
     * @param oldSealCode     被终结的旧铅封号
     * @param expectedVersion 复封时核对的当前阀门开度版本
     * @param newSealCode     新铅封号
     * @param verifier1       第一名复封核对人员
     * @param verifier2       第二名复封核对人员，须与第一名不同
     */
    public record ResealRequest(@NotBlank String requestId, @NotBlank String oldSealCode,
                                @NotNull Integer expectedVersion, @NotBlank String newSealCode,
                                @NotBlank String verifier1, @NotBlank String verifier2) {
    }

    /**
     * 受控调节请求。
     *
     * @param requestId       客户端幂等键
     * @param unitId          目标单元 ID
     * @param permitId        引用的临时解封单 ID
     * @param expectedVersion 期望的当前阀门开度版本
     * @param stepPercent     步进幅度，单位 %，(0, 10]
     */
    public record ControlledAdjustRequest(@NotBlank String requestId, @NotNull Long unitId,
                                          @NotNull Long permitId, @NotNull Integer expectedVersion,
                                          @NotNull BigDecimal stepPercent) {
    }

    /**
     * 铅封登记：唯一铅封号、当前开度版本与封存时刻。
     */
    @PostMapping("/seals")
    public ValveSeal registerSeal(@Valid @RequestBody RegisterSealRequest request) {
        return sealService.registerSeal(new SealService.RegisterSealCommand(
                request.requestId(), request.unitId(), request.sealCode(), request.expectedVersion()));
    }

    /**
     * 解封批准：两名不同授权人确认，固定 UTC 半开窗口与最多两次额度。
     */
    @PostMapping("/seals/unseals")
    public UnsealPermit approveUnseal(@Valid @RequestBody ApproveUnsealRequest request) {
        return sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                request.requestId(), request.sealCode(), request.expectedVersion(),
                request.approver1(), request.approver2(), request.windowStart(), request.windowEnd()));
    }

    /**
     * 撤销解封：仅生效单可撤销，撤销后禁止新调节，已发生调节不回拨。
     */
    @PostMapping("/seals/unseals/revoke")
    public UnsealPermit revokeUnseal(@Valid @RequestBody RevokeUnsealRequest request) {
        return sealService.revokeUnseal(new SealService.RevokeUnsealCommand(
                request.requestId(), request.permitId(), request.expectedVersion(), request.revokedBy()));
    }

    /**
     * 复封：两名不同人员核对版本并登记新铅封号，原子终结旧解封权限，保留封号链。
     */
    @PostMapping("/seals/reseal")
    public ValveSeal reseal(@Valid @RequestBody ResealRequest request) {
        return sealService.reseal(new SealService.ResealCommand(
                request.requestId(), request.oldSealCode(), request.expectedVersion(),
                request.newSealCode(), request.verifier1(), request.verifier2()));
    }

    /**
     * 受控调节：持有效解封单在窗口与额度内调节受控阀门。
     */
    @PostMapping("/stations/{stationId}/controlled-adjustments")
    public AdjustmentTask controlledAdjust(@PathVariable long stationId,
                                           @Valid @RequestBody ControlledAdjustRequest request) {
        return sealService.controlledAdjust(new SealService.ControlledAdjustCommand(
                request.requestId(), stationId, request.unitId(), request.permitId(),
                request.expectedVersion(), request.stepPercent()));
    }

    /**
     * 按铅封号查询台账。
     */
    @GetMapping("/seals/{sealCode}")
    public ValveSeal getSeal(@PathVariable String sealCode) {
        return sealService.getSeal(sealCode);
    }

    /**
     * 查询单元阀门完整封号链（含历史封号）。
     */
    @GetMapping("/units/{unitId}/seals")
    public List<ValveSeal> getSealChain(@PathVariable long unitId) {
        return sealService.getSealChain(unitId);
    }

    /**
     * 查询解封单。
     */
    @GetMapping("/permits/{permitId}")
    public UnsealPermit getPermit(@PathVariable long permitId) {
        return sealService.getPermit(permitId);
    }

    /**
     * 查询解封单额度消费轨迹。
     */
    @GetMapping("/permits/{permitId}/quota-events")
    public List<QuotaEvent> getQuotaEvents(@PathVariable long permitId) {
        return sealService.getQuotaEvents(permitId);
    }
}
