package com.example.starter.web;

import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.UnsealQuotaEvent;
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

import java.time.Instant;
import java.util.List;

/**
 * 铅封管理入口：铅封登记、临时解封批准/撤销、复封与额度轨迹查询。
 */
@RestController
@RequestMapping("/api/valves")
public class ValveSealController {

    private final SealService sealService;

    public ValveSealController(SealService sealService) {
        this.sealService = sealService;
    }

    /**
     * 铅封登记请求。
     *
     * @param requestId       客户端幂等键
     * @param sealNo          铅封号，全局唯一
     * @param expectedVersion 当前开度版本（无版本时为 0），不一致返回 409
     * @param operator        登记人
     */
    public record SealRegisterRequest(@NotBlank String requestId, @NotBlank String sealNo,
                                      @NotNull Integer expectedVersion, @NotBlank String operator) {
    }

    /**
     * 临时解封批准请求。
     *
     * @param requestId       客户端幂等键
     * @param sealNo          引用的铅封号，须为阀门当前有效铅封
     * @param expectedVersion 当前开度版本，不一致返回 409
     * @param authorizerA     授权人甲
     * @param authorizerB     授权人乙，须与甲不同
     * @param windowStart     有效窗口起点（UTC，含）
     * @param windowEnd       有效窗口终点（UTC，不含，恰到终点失效）
     * @param quotaTotal      调节额度，1~2 次
     */
    public record UnsealApproveRequest(@NotBlank String requestId, @NotBlank String sealNo,
                                       @NotNull Integer expectedVersion,
                                       @NotBlank String authorizerA, @NotBlank String authorizerB,
                                       @NotNull Instant windowStart, @NotNull Instant windowEnd,
                                       @NotNull Integer quotaTotal) {
    }

    /**
     * 解封撤销请求。
     *
     * @param requestId       客户端幂等键
     * @param expectedVersion 解封单当前行版本，不一致返回 409
     */
    public record UnsealRevokeRequest(@NotBlank String requestId, @NotNull Integer expectedVersion) {
    }

    /**
     * 复封请求。
     *
     * @param requestId       客户端幂等键
     * @param sealNo          新铅封号，全局唯一
     * @param expectedVersion 当前开度版本，不一致返回 409
     * @param confirmerA      核对人甲
     * @param confirmerB      核对人乙，须与甲不同
     */
    public record ResealRequest(@NotBlank String requestId, @NotBlank String sealNo,
                                @NotNull Integer expectedVersion,
                                @NotBlank String confirmerA, @NotBlank String confirmerB) {
    }

    /**
     * 登记铅封：登记唯一铅封号、当前开度版本与封存时刻（UTC）。
     */
    @PostMapping("/{valveId}/seals")
    public ValveSeal register(@PathVariable long valveId,
                              @Valid @RequestBody SealRegisterRequest request) {
        return sealService.registerSeal(valveId, request.requestId(), request.sealNo(),
                request.expectedVersion(), request.operator());
    }

    /**
     * 查询阀门完整封号链（含历史），复封后旧封号保留可查。
     */
    @GetMapping("/{valveId}/seals")
    public List<ValveSeal> sealChain(@PathVariable long valveId) {
        return sealService.getSealChain(valveId);
    }

    /**
     * 批准临时解封：两名不同授权人共同确认，固定 UTC 半开窗口与最多两次额度；
     * 解封生效不改变阀门开度。
     */
    @PostMapping("/{valveId}/unseal-orders")
    public UnsealOrder approve(@PathVariable long valveId,
                               @Valid @RequestBody UnsealApproveRequest request) {
        return sealService.approveUnseal(valveId, request.requestId(), request.sealNo(),
                request.expectedVersion(), request.authorizerA(), request.authorizerB(),
                request.windowStart(), request.windowEnd(), request.quotaTotal());
    }

    /**
     * 复封：两名不同人员核对当前开度版本并登记新铅封号，原子终结旧解封权限。
     */
    @PostMapping("/{valveId}/reseal")
    public ValveSeal reseal(@PathVariable long valveId,
                            @Valid @RequestBody ResealRequest request) {
        return sealService.reseal(valveId, request.requestId(), request.sealNo(),
                request.expectedVersion(), request.confirmerA(), request.confirmerB());
    }

    /**
     * 查询解封单详情。
     */
    @GetMapping("/unseal-orders/{orderId}")
    public UnsealOrder unsealOrder(@PathVariable long orderId) {
        return sealService.getUnsealOrder(orderId);
    }

    /**
     * 撤销解封单：撤销后不再允许新的受控调节，已改开度不回拨。
     */
    @PostMapping("/unseal-orders/{orderId}/revoke")
    public UnsealOrder revoke(@PathVariable long orderId,
                              @Valid @RequestBody UnsealRevokeRequest request) {
        return sealService.revokeUnseal(orderId, request.requestId(), request.expectedVersion());
    }

    /**
     * 查询解封单额度轨迹（授予/消费/撤销/终结）。
     */
    @GetMapping("/unseal-orders/{orderId}/quota-events")
    public List<UnsealQuotaEvent> quotaEvents(@PathVariable long orderId) {
        return sealService.getQuotaEvents(orderId);
    }
}
