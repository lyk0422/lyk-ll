package com.example.starter.irrigation.api;

import com.example.starter.irrigation.dto.ExtraPurchaseContracts.ExtraPurchaseRequest;
import com.example.starter.irrigation.dto.ExtraPurchaseContracts.ExtraPurchaseView;
import com.example.starter.irrigation.dto.GroupContracts.CreateGroupRequest;
import com.example.starter.irrigation.dto.GroupContracts.GroupView;
import com.example.starter.irrigation.dto.MeteringContracts.MeteringRequest;
import com.example.starter.irrigation.dto.MeteringContracts.MeteringView;
import com.example.starter.irrigation.dto.PlanContracts.PlanView;
import com.example.starter.irrigation.dto.PlanContracts.PublishPlanRequest;
import com.example.starter.irrigation.dto.SettlementContracts.SealRequest;
import com.example.starter.irrigation.dto.SettlementContracts.SettlementView;
import com.example.starter.irrigation.service.ExtraPurchaseService;
import com.example.starter.irrigation.service.GroupService;
import com.example.starter.irrigation.service.MeteringService;
import com.example.starter.irrigation.service.PlanService;
import com.example.starter.irrigation.service.SettlementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 灌区轮灌配水计划与欠供结转服务 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class IrrigationController {

    private final GroupService groupService;
    private final PlanService planService;
    private final MeteringService meteringService;
    private final SettlementService settlementService;
    private final ExtraPurchaseService extraPurchaseService;

    public IrrigationController(GroupService groupService,
                                PlanService planService,
                                MeteringService meteringService,
                                SettlementService settlementService,
                                ExtraPurchaseService extraPurchaseService) {
        this.groupService = groupService;
        this.planService = planService;
        this.meteringService = meteringService;
        this.settlementService = settlementService;
        this.extraPurchaseService = extraPurchaseService;
    }

    /**
     * 创建轮灌组（含组内斗口编组）。
     */
    @PostMapping("/groups")
    public ResponseEntity<GroupView> createGroup(@Valid @RequestBody CreateGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groupService.create(request));
    }

    /**
     * 查询轮灌组及斗口。
     */
    @GetMapping("/groups/{groupId}")
    public GroupView getGroup(@PathVariable long groupId) {
        return groupService.get(groupId);
    }

    /**
     * 发布配水计划（requestId 幂等；同轮同组唯一生效版本）。
     */
    @PostMapping("/plans")
    public ResponseEntity<PlanView> publishPlan(@Valid @RequestBody PublishPlanRequest request) {
        PlanView view = planService.publish(request);
        HttpStatus status = view.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(view);
    }

    /**
     * 查询同轮同组全部计划版本（旧版本可查）。
     */
    @GetMapping("/plans")
    public List<PlanView> listPlans(@RequestParam long groupId, @RequestParam int roundNo) {
        return planService.listVersions(groupId, roundNo);
    }

    /**
     * 查询当前生效计划版本。
     */
    @GetMapping("/plans/effective")
    public PlanView getEffectivePlan(@RequestParam long groupId, @RequestParam int roundNo) {
        return planService.getEffective(groupId, roundNo);
    }

    /**
     * 上报斗口实供计量快照（按斗口+轮次去重；封存后仅记迟到事件）。
     */
    @PostMapping("/meterings")
    public MeteringView reportMetering(@Valid @RequestBody MeteringRequest request) {
        return meteringService.report(request);
    }

    /**
     * 结算并封存轮次欠供结转（封存为终态，重复封存幂等）。
     */
    @PostMapping("/settlements/seal")
    public SettlementView seal(@Valid @RequestBody SealRequest request) {
        return settlementService.seal(request);
    }

    /**
     * 查询轮次结算封存结果。
     */
    @GetMapping("/settlements")
    public SettlementView getSettlement(@RequestParam long groupId, @RequestParam int roundNo) {
        return settlementService.get(groupId, roundNo);
    }

    /**
     * 临时加购水量（优先满足欠供斗口，按欠供比例分配；requestId 幂等）。
     */
    @PostMapping("/extra-purchases")
    public ResponseEntity<ExtraPurchaseView> purchase(@Valid @RequestBody ExtraPurchaseRequest request) {
        ExtraPurchaseView view = extraPurchaseService.purchase(request);
        HttpStatus status = view.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(view);
    }
}
