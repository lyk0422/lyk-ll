package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 施工段闭锁与可用区域 API：受控跑道登记/长度变更、施工单登记/修改/生效/撤回、分段验收、可用区间查询。
 */
@RestController
@RequestMapping("/api")
public class ConstructionController {

    private final ConstructionService service;

    public ConstructionController(ConstructionService service) {
        this.service = service;
    }

    /** 登记受控跑道（正整数米长度）。requestId 幂等。 */
    @PostMapping("/controlled-runways")
    @ResponseStatus(HttpStatus.CREATED)
    public ControlledRunwayResponse registerRunway(@Valid @RequestBody RunwayRegisterRequest request) {
        return service.registerRunway(request);
    }

    /** 变更跑道长度：存在活动施工后不许缩改。expectedVersion 乐观锁。 */
    @PutMapping("/controlled-runways/{runwayId}")
    public ControlledRunwayResponse resizeRunway(@PathVariable String runwayId,
                                                 @Valid @RequestBody RunwayResizeRequest request) {
        return service.resizeRunway(runwayId, request);
    }

    /** 受控跑道详情。 */
    @GetMapping("/controlled-runways/{runwayId}")
    public ControlledRunwayResponse getRunway(@PathVariable String runwayId) {
        return service.getRunway(runwayId);
    }

    /** 可用区域查询：所有活动施工区间并集的补集，重叠不重复计长度。 */
    @GetMapping("/controlled-runways/{runwayId}/available-areas")
    public AvailableAreasResponse availableAreas(@PathVariable String runwayId) {
        return service.getAvailableAreas(runwayId);
    }

    /** 登记施工单：1至10个不同段号，同单内部重叠拒绝。requestId 幂等。 */
    @PostMapping("/construction-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ConstructionOrderResponse createOrder(@Valid @RequestBody ConstructionOrderCreateRequest request) {
        return service.createOrder(request);
    }

    /** 施工单详情。 */
    @GetMapping("/construction-orders/{orderId}")
    public ConstructionOrderResponse getOrder(@PathVariable long orderId) {
        return service.getOrder(orderId);
    }

    /** 按跑道列出施工单。 */
    @GetMapping("/construction-orders")
    public List<ConstructionOrderResponse> listOrders(@RequestParam String runwayId) {
        return service.listOrders(runwayId);
    }

    /** 修改施工单施工段：仅未生效单据；已生效不得缩段。expectedVersion 乐观锁。 */
    @PutMapping("/construction-orders/{orderId}")
    public ConstructionOrderResponse modifyOrder(@PathVariable long orderId,
                                                 @Valid @RequestBody ConstructionOrderModifyRequest request) {
        return service.modifyOrder(orderId, request);
    }

    /** 施工单生效：冻结区间、闭锁整跑道并原子抢占该跑道例行计划。 */
    @PostMapping("/construction-orders/{orderId}/activate")
    public ConstructionOrderResponse activateOrder(@PathVariable long orderId,
                                                   @Valid @RequestBody ConstructionActivateRequest request) {
        return service.activateOrder(orderId, request);
    }

    /** 撤回施工单：仅未生效单据可撤回。 */
    @PostMapping("/construction-orders/{orderId}/withdraw")
    public ConstructionOrderResponse withdrawOrder(@PathVariable long orderId,
                                                   @Valid @RequestBody ConstructionWithdrawRequest request) {
        return service.withdrawOrder(orderId, request);
    }

    /** 提交分段验收清场凭据：两个不同验收人齐全后段完成；迟到凭据仅登记留痕。requestId 幂等。 */
    @PostMapping("/construction-orders/{orderId}/segments/{segmentNo}/acceptances")
    @ResponseStatus(HttpStatus.CREATED)
    public AcceptanceResponse submitAcceptance(@PathVariable long orderId, @PathVariable int segmentNo,
                                               @Valid @RequestBody AcceptanceRequest request) {
        return service.submitAcceptance(orderId, segmentNo, request);
    }
}
