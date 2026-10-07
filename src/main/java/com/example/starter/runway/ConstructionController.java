package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 跑道施工段闭锁与可用区域查询 API：
 * 受控跑道登记/长度修改，施工单登记/修改/生效/撤回，段验收清场凭据，可用区间查询。
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
    public RunwayResponse registerRunway(@Valid @RequestBody RegisterRunwayRequest request) {
        return service.registerRunway(request);
    }

    /** 受控跑道详情。 */
    @GetMapping("/controlled-runways/{runwayId}")
    public RunwayResponse getRunway(@PathVariable String runwayId) {
        return service.getRunway(runwayId);
    }

    /** 修改跑道长度：存在活动施工后不许缩改。expectedVersion 乐观锁，requestId 幂等。 */
    @PutMapping("/controlled-runways/{runwayId}")
    public RunwayResponse updateRunwayLength(@PathVariable String runwayId,
                                             @Valid @RequestBody UpdateRunwayLengthRequest request) {
        return service.updateRunwayLength(runwayId, request);
    }

    /** 可用区域查询：活动施工区间并集的补集，重叠不重复计长度，空可用返回空列表。 */
    @GetMapping("/controlled-runways/{runwayId}/usable-segments")
    public UsableSegmentsResponse usableSegments(@PathVariable String runwayId) {
        return service.getUsableSegments(runwayId);
    }

    /** 登记施工单：1至10个不同段号，单内重叠拒绝、端点相接允许。requestId 幂等。 */
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

    /** 修改施工单段集合：仅未生效单可修改，已生效不得缩段。expectedVersion 乐观锁。 */
    @PutMapping("/construction-orders/{orderId}")
    public ConstructionOrderResponse updateOrder(@PathVariable long orderId,
                                                 @Valid @RequestBody ConstructionOrderUpdateRequest request) {
        return service.updateOrder(orderId, request);
    }

    /** 施工生效：冻结区间、阻断整跑道新使用，原子抢占本跑道例行计划。expectedVersion 乐观锁。 */
    @PostMapping("/construction-orders/{orderId}/activate")
    public ConstructionOrderResponse activateOrder(@PathVariable long orderId,
                                                   @Valid @RequestBody OrderActionRequest request) {
        return service.activateOrder(orderId, request);
    }

    /** 撤回施工单：仅未生效单可撤回，已生效不得直接删除。expectedVersion 乐观锁。 */
    @PostMapping("/construction-orders/{orderId}/withdraw")
    public ConstructionOrderResponse withdrawOrder(@PathVariable long orderId,
                                                   @Valid @RequestBody OrderActionRequest request) {
        return service.withdrawOrder(orderId, request);
    }

    /** 提交清场凭据：两个不同验收人后段完成；迟到凭据只读留痕。requestId 幂等。 */
    @PostMapping("/construction-orders/{orderId}/segments/{segmentNo}/clearances")
    @ResponseStatus(HttpStatus.CREATED)
    public ClearanceResponse submitClearance(@PathVariable long orderId, @PathVariable int segmentNo,
                                             @Valid @RequestBody ClearanceRequest request) {
        return service.submitClearance(orderId, segmentNo, request);
    }
}
