package com.example.starter.web;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.UnsealQuotaUsage;
import com.example.starter.domain.ValveSeal;
import com.example.starter.service.AdjustmentService;
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
 * 铅封管理入口：登记、临时解封批准、撤销、复封、受控调节与额度轨迹查询。
 * 新写操作均携带 requestId 幂等键与 expectedVersion 乐观锁版本。
 */
@RestController
@RequestMapping("/api")
public class SealController {

    private final SealService sealService;
    private final AdjustmentService adjustmentService;

    public SealController(SealService sealService, AdjustmentService adjustmentService) {
        this.sealService = sealService;
        this.adjustmentService = adjustmentService;
    }

    /**
     * 铅封登记请求。
     *
     * @param requestId    客户端幂等键
     * @param sealNo       铅封号，全局唯一
     * @param valveVersion 登记时核对的开度版本（无版本为 0）
     */
    public record SealRegisterRequest(@NotBlank String requestId, @NotBlank String sealNo,
                                      @NotNull Integer valveVersion) {
    }

    /**
     * 临时解封批准请求。
     *
     * @param requestId       客户端幂等键
     * @param expectedVersion 铅封乐观锁版本
     * @param valveVersion    引用的阀门开度版本
     * @param approverA       授权人甲，须与授权人乙不同
     * @param approverB       授权人乙，须与授权人甲不同
     * @param windowStart     有效窗口起点（UTC，半开区间，含）
     * @param windowEnd       有效窗口终点（UTC，半开区间，不含）
     */
    public record UnsealApproveRequest(@NotBlank String requestId, @NotNull Integer expectedVersion,
                                       @NotNull Integer valveVersion,
                                       @NotBlank String approverA, @NotBlank String approverB,
                                       @NotNull Instant windowStart, @NotNull Instant windowEnd) {
    }

    /**
     * 解封撤销请求。
     *
     * @param requestId       客户端幂等键
     * @param expectedVersion 解封单乐观锁版本
     */
    public record UnsealRevokeRequest(@NotBlank String requestId, @NotNull Integer expectedVersion) {
    }

    /**
     * 复封请求。
     *
     * @param requestId       客户端幂等键
     * @param expectedVersion 当前完整铅封的乐观锁版本
     * @param newSealNo       新铅封号，全局唯一
     * @param checkerA        核对人甲，须与核对人乙不同
     * @param checkerB        核对人乙，须与核对人甲不同
     * @param valveVersion    核对的当前开度版本
     */
    public record ResealRequest(@NotBlank String requestId, @NotNull Integer expectedVersion,
                                @NotBlank String newSealNo,
                                @NotBlank String checkerA, @NotBlank String checkerB,
                                @NotNull Integer valveVersion) {
    }

    /**
     * 受控调节请求。
     *
     * @param requestId       客户端幂等键
     * @param expectedVersion 解封单乐观锁版本
     * @param stepPercent     步进幅度，单位 %，(0, 10]，越界由服务层返回 422
     */
    public record ControlledAdjustmentRequest(@NotBlank String requestId,
                                              @NotNull Integer expectedVersion,
                                              @NotNull BigDecimal stepPercent) {
    }

    /**
     * 铅封登记：登记唯一铅封号、当前开度版本与封存时刻。
     */
    @PostMapping("/valves/{valveId}/seals")
    public ValveSeal registerSeal(@PathVariable long valveId,
                                  @Valid @RequestBody SealRegisterRequest request) {
        return sealService.registerSeal(valveId, request.requestId(),
                request.sealNo(), request.valveVersion());
    }

    /**
     * 查询阀门完整封号链（含历史）。
     */
    @GetMapping("/valves/{valveId}/seals")
    public List<ValveSeal> sealChain(@PathVariable long valveId) {
        return sealService.getSealChain(valveId);
    }

    /**
     * 查询阀门当前完整有效的铅封。
     */
    @GetMapping("/valves/{valveId}/seal")
    public ValveSeal activeSeal(@PathVariable long valveId) {
        return sealService.getActiveSeal(valveId);
    }

    /**
     * 复封：两名不同人员核对当前开度版本并登记新铅封号，原子终结旧解封权限。
     */
    @PostMapping("/valves/{valveId}/reseals")
    public ValveSeal reseal(@PathVariable long valveId,
                            @Valid @RequestBody ResealRequest request) {
        return sealService.reseal(valveId, request.requestId(), request.expectedVersion(),
                request.newSealNo(), request.checkerA(), request.checkerB(), request.valveVersion());
    }

    /**
     * 临时解封批准：两名不同授权人共同确认，固定 UTC 半开窗口与两次调节额度。
     */
    @PostMapping("/seals/{sealId}/unseals")
    public UnsealOrder approveUnseal(@PathVariable long sealId,
                                     @Valid @RequestBody UnsealApproveRequest request) {
        return sealService.approveUnseal(sealId, request.requestId(), request.expectedVersion(),
                request.valveVersion(), request.approverA(), request.approverB(),
                request.windowStart(), request.windowEnd());
    }

    /**
     * 查询解封单详情。
     */
    @GetMapping("/unseals/{unsealId}")
    public UnsealOrder unseal(@PathVariable long unsealId) {
        return sealService.getUnseal(unsealId);
    }

    /**
     * 撤销解封单：仅禁止新调节，不回拨已改开度。
     */
    @PostMapping("/unseals/{unsealId}/revoke")
    public UnsealOrder revokeUnseal(@PathVariable long unsealId,
                                    @Valid @RequestBody UnsealRevokeRequest request) {
        return sealService.revokeUnseal(unsealId, request.requestId(), request.expectedVersion());
    }

    /**
     * 受控调节：解封窗口与额度内下达调节令，原子更新开度、写版本并消费一次额度。
     */
    @PostMapping("/unseals/{unsealId}/adjustments")
    public AdjustmentTask controlledAdjust(@PathVariable long unsealId,
                                           @Valid @RequestBody ControlledAdjustmentRequest request) {
        return adjustmentService.issueControlledAdjustment(unsealId, request.requestId(),
                request.expectedVersion(), request.stepPercent());
    }

    /**
     * 查询解封单的额度消费轨迹。
     */
    @GetMapping("/unseals/{unsealId}/quota-usages")
    public List<UnsealQuotaUsage> quotaTrail(@PathVariable long unsealId) {
        return sealService.getQuotaTrail(unsealId);
    }
}
