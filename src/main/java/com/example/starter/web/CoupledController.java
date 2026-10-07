package com.example.starter.web;

import com.example.starter.service.CoupledService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
 * 耦合联调入口：联调组建/改/解除、全组目标开度原子提交与留痕查询。
 * 建组与联调提交携带 requestId 幂等键；改组/解除携带 expectedVersion 乐观锁版本。
 */
@RestController
@RequestMapping("/api")
public class CoupledController {

    private final CoupledService coupledService;

    public CoupledController(CoupledService coupledService) {
        this.coupledService = coupledService;
    }

    /**
     * 建组成员。
     *
     * @param valveId 成员阀门 ID
     * @param weight  联调权重，正整数 1~10，建组时冻结
     */
    public record MemberRequest(long valveId, int weight) {
    }

    /**
     * 建组请求。
     *
     * @param requestId 客户端幂等键
     * @param members   2~5 只不同阀及权重
     */
    public record GroupCreateRequest(@NotBlank String requestId,
                                     @NotEmpty List<@Valid MemberRequest> members) {
    }

    /**
     * 改组/解除请求。
     *
     * @param expectedVersion 联调单乐观锁版本，成功加 1，旧版 409
     * @param members         改组后的成员（解除请求忽略）
     */
    public record GroupModifyRequest(@NotNull Integer expectedVersion,
                                     List<@Valid MemberRequest> members) {
    }

    /**
     * 联调逐阀目标。
     *
     * @param valveId        成员阀门 ID
     * @param targetOpening  目标开度，0~100 整数百分点
     * @param currentVersion 客户端持有的阀门当前开度版本（无版本为 0）
     * @param unsealId       铅封阀提供的解封单 ID；未封阀为空
     */
    public record TargetRequest(long valveId, int targetOpening, int currentVersion,
                                Long unsealId) {
    }

    /**
     * 联调提交请求。
     *
     * @param requestId 客户端幂等键
     * @param adjustAt  客户端声明的联调时刻（UTC），参与幂等指纹
     * @param targets   全组目标开度与逐阀当前版本，须恰好覆盖全组成员阀
     */
    public record CoupledAdjustmentRequest(@NotBlank String requestId,
                                           @NotNull Instant adjustAt,
                                           @NotEmpty List<@Valid TargetRequest> targets) {
    }

    /**
     * 建立联调组：同站 2~5 只不同阀，冻结权重与当前开度版本，阀只能属于一个活动组。
     */
    @PostMapping("/stations/{stationId}/coupled-groups")
    public CoupledService.GroupView createGroup(@PathVariable long stationId,
                                                @Valid @RequestBody GroupCreateRequest request) {
        return coupledService.createGroup(stationId, request.requestId(),
                toMemberCommands(request.members()));
    }

    /**
     * 查询联调组及冻结成员。
     */
    @GetMapping("/coupled-groups/{groupId}")
    public CoupledService.GroupView group(@PathVariable long groupId) {
        return coupledService.getGroup(groupId);
    }

    /**
     * 修改联调组：整体替换成员并重新冻结开度版本，expectedVersion 旧版 409。
     */
    @PostMapping("/coupled-groups/{groupId}/modify")
    public CoupledService.GroupView modifyGroup(@PathVariable long groupId,
                                                @Valid @RequestBody GroupModifyRequest request) {
        return coupledService.modifyGroup(groupId, request.expectedVersion(),
                toMemberCommands(request.members()));
    }

    /**
     * 解除联调组：只释放组归属，不回拨开度或返还已消费额度。
     */
    @PostMapping("/coupled-groups/{groupId}/dissolve")
    public CoupledService.GroupView dissolveGroup(@PathVariable long groupId,
                                                  @Valid @RequestBody GroupModifyRequest request) {
        return coupledService.dissolveGroup(groupId, request.expectedVersion());
    }

    /**
     * 联调提交：全组目标开度原子生效；缺阀、旧版本、失效解封或任一超限全组零变化。
     */
    @PostMapping("/coupled-groups/{groupId}/adjustments")
    public CoupledService.AdjustmentView submitAdjustment(
            @PathVariable long groupId,
            @Valid @RequestBody CoupledAdjustmentRequest request) {
        return coupledService.submitAdjustment(groupId, request.requestId(), request.adjustAt(),
                request.targets().stream()
                        .map(t -> new CoupledService.TargetCommand(t.valveId(), t.targetOpening(),
                                t.currentVersion(), t.unsealId()))
                        .toList());
    }

    /**
     * 查询联调组全部联调单（含逐阀明细，可复算净加权开度及逐阀额度）。
     */
    @GetMapping("/coupled-groups/{groupId}/adjustments")
    public List<CoupledService.AdjustmentView> groupAdjustments(@PathVariable long groupId) {
        return coupledService.listGroupAdjustments(groupId);
    }

    /**
     * 查询联调单及逐阀明细。
     */
    @GetMapping("/coupled-adjustments/{coupledId}")
    public CoupledService.AdjustmentView adjustment(@PathVariable long coupledId) {
        return coupledService.getAdjustment(coupledId);
    }

    private static List<CoupledService.MemberCommand> toMemberCommands(List<MemberRequest> members) {
        if (members == null) {
            return List.of();
        }
        return members.stream()
                .map(m -> new CoupledService.MemberCommand(m.valveId(), m.weight()))
                .toList();
    }
}
