package com.example.starter.web;

import com.example.starter.domain.AdjustmentGroup;
import com.example.starter.domain.CoupledAdjustment;
import com.example.starter.service.CoupledAdjustmentService;
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
 * 联调入口：联调组建制/查询/解除与全组原子联调提交。
 * 新写操作均携带 requestId 幂等键与 expectedVersion 乐观锁版本。
 */
@RestController
@RequestMapping("/api")
public class GroupController {

    private final CoupledAdjustmentService coupledService;

    public GroupController(CoupledAdjustmentService coupledService) {
        this.coupledService = coupledService;
    }

    /**
     * 建组成员请求项。
     *
     * @param valveId 成员阀门 ID
     * @param weight  阀门权重，正整数 1~10，越界由服务层返回 422
     */
    public record MemberRequest(@NotNull Long valveId, @NotNull Integer weight) {
    }

    /**
     * 建组请求。
     *
     * @param requestId 客户端幂等键
     * @param members   成员列表，2~5 只不同阀
     */
    public record GroupCreateRequest(@NotBlank String requestId,
                                     @NotNull List<@Valid MemberRequest> members) {
    }

    /**
     * 联调解除请求。
     *
     * @param requestId       客户端幂等键
     * @param expectedVersion 联调组乐观锁版本
     */
    public record GroupDissolveRequest(@NotBlank String requestId,
                                       @NotNull Integer expectedVersion) {
    }

    /**
     * 联调逐阀目标请求项。
     *
     * @param valveId             成员阀门 ID
     * @param expectedValveVersion 逐阀当前开度版本（无版本为 0）
     * @param targetOpening       目标开度，0~100 整数百分点
     * @param unsealId            铅封阀提供的解封单 ID；未封阀可空
     * @param unsealVersion       解封单乐观锁版本；未封阀可空
     */
    public record CoupledTargetRequest(@NotNull Long valveId, @NotNull Integer expectedValveVersion,
                                       @NotNull Integer targetOpening,
                                       Long unsealId, Integer unsealVersion) {
    }

    /**
     * 联调提交请求。
     *
     * @param requestId       客户端幂等键
     * @param expectedVersion 联调组乐观锁版本，旧版 409
     * @param requestedAt     请求携带的 UTC 时刻，参与幂等指纹
     * @param targets         全组逐阀目标，须恰好覆盖全部成员阀
     */
    public record CoupledSubmitRequest(@NotBlank String requestId,
                                       @NotNull Integer expectedVersion,
                                       @NotNull Instant requestedAt,
                                       @NotNull List<@Valid CoupledTargetRequest> targets) {
    }

    /**
     * 建立联调组：同站 2~5 只不同阀，冻结各阀权重与当前开度版本。
     */
    @PostMapping("/stations/{stationId}/adjustment-groups")
    public CoupledAdjustmentService.GroupView createGroup(@PathVariable long stationId,
                                                          @Valid @RequestBody GroupCreateRequest request) {
        List<CoupledAdjustmentService.MemberSpec> members = request.members().stream()
                .map(m -> new CoupledAdjustmentService.MemberSpec(m.valveId(), m.weight()))
                .toList();
        return coupledService.createGroup(stationId, request.requestId(), members);
    }

    /**
     * 查询联调组详情：成员冻结权重/版本与当前开度/版本，可复算净加权开度。
     */
    @GetMapping("/adjustment-groups/{groupId}")
    public CoupledAdjustmentService.GroupView getGroup(@PathVariable long groupId) {
        return coupledService.getGroup(groupId);
    }

    /**
     * 解除联调组：仅释放组归属，不回拨开度或返还已消费额度。
     */
    @PostMapping("/adjustment-groups/{groupId}/dissolve")
    public AdjustmentGroup dissolveGroup(@PathVariable long groupId,
                                         @Valid @RequestBody GroupDissolveRequest request) {
        return coupledService.dissolveGroup(groupId, request.requestId(), request.expectedVersion());
    }

    /**
     * 联调提交：全组目标开度原子生效，任一校验失败全组零变化。
     */
    @PostMapping("/adjustment-groups/{groupId}/coupled-adjustments")
    public CoupledAdjustmentService.CoupledView submit(@PathVariable long groupId,
                                                       @Valid @RequestBody CoupledSubmitRequest request) {
        List<CoupledAdjustmentService.TargetSpec> targets = request.targets().stream()
                .map(t -> new CoupledAdjustmentService.TargetSpec(t.valveId(),
                        t.expectedValveVersion(), t.targetOpening(), t.unsealId(), t.unsealVersion()))
                .toList();
        return coupledService.submit(groupId, request.requestId(), request.expectedVersion(),
                request.requestedAt(), targets);
    }

    /**
     * 查询联调组的全部联调记录。
     */
    @GetMapping("/adjustment-groups/{groupId}/coupled-adjustments")
    public List<CoupledAdjustment> listCoupled(@PathVariable long groupId) {
        return coupledService.listCoupledByGroup(groupId);
    }

    /**
     * 查询联调记录及逐阀明细（联调前后开度保留，可复算净加权开度及逐阀额度）。
     */
    @GetMapping("/coupled-adjustments/{coupledId}")
    public CoupledAdjustmentService.CoupledView getCoupled(@PathVariable long coupledId) {
        return coupledService.getCoupled(coupledId);
    }
}
