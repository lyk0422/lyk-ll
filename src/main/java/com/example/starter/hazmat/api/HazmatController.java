package com.example.starter.hazmat.api;

import java.time.LocalDateTime;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.hazmat.dto.ApplyRequest;
import com.example.starter.hazmat.dto.CheckinRequest;
import com.example.starter.hazmat.dto.IdempotentRequest;
import com.example.starter.hazmat.dto.RelayCheckinRequest;
import com.example.starter.hazmat.dto.RelayRequest;
import com.example.starter.hazmat.dto.RerouteRequest;
import com.example.starter.hazmat.service.HazmatService;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 危化品运输监管 REST 入口。
 *
 * <ul>
 *   <li>POST /api/shipments/apply 运输申请（requestId 幂等）</li>
 *   <li>POST /api/shipments/{no}/approvals/decide?approved=true|false 路线核准</li>
 *   <li>POST /api/shipments/{no}/dispatch 发运</li>
 *   <li>POST /api/shipments/{no}/checkins 押运打卡（运单+途经点序号去重）</li>
 *   <li>POST /api/shipments/{no}/reroute 紧急改线申请</li>
 *   <li>POST /api/shipments/{no}/approvals/{id}/approve 改线核准（原子切换）</li>
 *   <li>POST /api/shipments/{no}/complete 到达核销（结案终态）</li>
 *   <li>POST /api/shipments/{no}/relays 设置安全接力点（夜间禁行起点前，requestId 幂等）</li>
 *   <li>POST /api/shipments/{no}/relays/{relayId}/checkins 接力段双人打卡（运单+途经点序号去重）</li>
 *   <li>GET  /api/shipments/{no} 运单详情（状态/途经点/接力段/处置/事件，可复算视图）</li>
 *   <li>POST /api/monitor/scan?at=yyyy-MM-ddTHH:mm:ss 超时/失联监控扫描</li>
 * </ul>
 */
@RestController
public class HazmatController {

    private final HazmatService service;

    public HazmatController(HazmatService service) {
        this.service = service;
    }

    @PostMapping("/api/shipments/apply")
    public ResponseEntity<Map<String, Object>> apply(@Valid @RequestBody ApplyRequest request) {
        return toEntity(service.apply(request));
    }

    @PostMapping("/api/shipments/{shipmentNo}/approvals/decide")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable String shipmentNo,
                                                      @RequestParam(defaultValue = "true") boolean approved) {
        return toEntity(service.decideApproval(shipmentNo, approved));
    }

    @PostMapping("/api/shipments/{shipmentNo}/dispatch")
    public ResponseEntity<Map<String, Object>> dispatch(@PathVariable String shipmentNo,
                                                        @Valid @RequestBody IdempotentRequest request) {
        return toEntity(service.dispatch(shipmentNo, request.requestId()));
    }

    @PostMapping("/api/shipments/{shipmentNo}/checkins")
    public ResponseEntity<Map<String, Object>> checkin(@PathVariable String shipmentNo,
                                                       @Valid @RequestBody CheckinRequest request) {
        return toEntity(service.checkin(shipmentNo, request));
    }

    @PostMapping("/api/shipments/{shipmentNo}/reroute")
    public ResponseEntity<Map<String, Object>> reroute(@PathVariable String shipmentNo,
                                                       @Valid @RequestBody RerouteRequest request) {
        return toEntity(service.reroute(shipmentNo, request));
    }

    @PostMapping("/api/shipments/{shipmentNo}/approvals/{approvalId}/approve")
    public ResponseEntity<Map<String, Object>> approveReroute(@PathVariable String shipmentNo,
                                                              @PathVariable long approvalId) {
        return toEntity(service.approveReroute(shipmentNo, approvalId));
    }

    @PostMapping("/api/shipments/{shipmentNo}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable String shipmentNo,
                                                        @Valid @RequestBody IdempotentRequest request) {
        return toEntity(service.complete(shipmentNo, request.requestId()));
    }

    @PostMapping("/api/shipments/{shipmentNo}/relays")
    public ResponseEntity<Map<String, Object>> createRelay(@PathVariable String shipmentNo,
                                                           @Valid @RequestBody RelayRequest request) {
        return toEntity(service.createRelay(shipmentNo, request));
    }

    @PostMapping("/api/shipments/{shipmentNo}/relays/{relayId}/checkins")
    public ResponseEntity<Map<String, Object>> relayCheckin(@PathVariable String shipmentNo,
                                                            @PathVariable long relayId,
                                                            @Valid @RequestBody RelayCheckinRequest request) {
        return toEntity(service.relayCheckin(shipmentNo, relayId, request));
    }

    @GetMapping("/api/shipments/{shipmentNo}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String shipmentNo) {
        return toEntity(service.getShipment(shipmentNo));
    }

    @PostMapping("/api/monitor/scan")
    public ResponseEntity<Map<String, Object>> scan(@RequestParam(required = false) LocalDateTime at) {
        return toEntity(service.scanTimeouts(at));
    }

    private ResponseEntity<Map<String, Object>> toEntity(ServiceResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
