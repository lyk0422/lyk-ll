package com.example.starter.groundwire;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.groundwire.GroundWireDtos.ApplyRequest;
import com.example.starter.groundwire.GroundWireDtos.BorrowItem;
import com.example.starter.groundwire.GroundWireDtos.BorrowRequest;
import com.example.starter.groundwire.GroundWireDtos.GroundWireDetailView;
import com.example.starter.groundwire.GroundWireDtos.GroundWireView;
import com.example.starter.groundwire.GroundWireDtos.RemoveRequest;
import com.example.starter.groundwire.GroundWireDtos.ReturnRequest;
import com.example.starter.idem.IdempotencyService;
import com.example.starter.idem.StoredResponse;

/**
 * 临时接地线接口：借用、挂接、拆除、归还核销与查询。
 * 写操作以 requestId 幂等，指纹为长度前缀结构化编码，覆盖版本、集合与精确时刻。
 */
@RestController
@RequestMapping("/api/ground-wires")
public class GroundWireController {

    private final GroundWireService service;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public GroundWireController(GroundWireService service, IdempotencyService idempotency,
                                ObjectMapper mapper) {
        this.service = service;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    /** 批量借用：绑定未归档且未作废的操作票及间隔，任一不可用整批拒绝。 */
    @PostMapping("/borrow")
    public ResponseEntity<String> borrow(@Valid @RequestBody BorrowRequest req) {
        List<String> fields = new ArrayList<>(List.of(
                String.valueOf(req.ticketId()), req.intervalId(), String.valueOf(req.operatedAt())));
        for (BorrowItem item : req.wires()) {
            fields.add(item.wireNo());
            fields.add(String.valueOf(item.expectedVersion()));
        }
        String fingerprint = IdempotencyService.structuredFingerprint(
                "GROUND_WIRE_BORROW", req.requestId(), fields);
        StoredResponse response = idempotency.handle("GROUND_WIRE_BORROW", req.requestId(), fingerprint,
                () -> toJson(service.borrow(req)));
        return respond(response);
    }

    /** 挂接：引用本票步骤，双人确认，保存不可覆盖证据。 */
    @PostMapping("/{wireNo}/apply")
    public ResponseEntity<String> apply(@PathVariable String wireNo,
                                        @Valid @RequestBody ApplyRequest req) {
        String fingerprint = IdempotencyService.structuredFingerprint("GROUND_WIRE_APPLY", req.requestId(),
                List.of(wireNo, String.valueOf(req.ticketId()), String.valueOf(req.stepSeq()),
                        req.operatorId(), req.guardianId(), String.valueOf(req.expectedVersion()),
                        String.valueOf(req.operatedAt())));
        StoredResponse response = idempotency.handle("GROUND_WIRE_APPLY", req.requestId(), fingerprint,
                () -> toJson(service.apply(wireNo, req)));
        return respond(response);
    }

    /** 拆除：引用本票步骤，双人确认，保存不可覆盖证据。 */
    @PostMapping("/{wireNo}/remove")
    public ResponseEntity<String> remove(@PathVariable String wireNo,
                                         @Valid @RequestBody RemoveRequest req) {
        String fingerprint = IdempotencyService.structuredFingerprint("GROUND_WIRE_REMOVE", req.requestId(),
                List.of(wireNo, String.valueOf(req.ticketId()), String.valueOf(req.stepSeq()),
                        req.operatorId(), req.guardianId(), String.valueOf(req.expectedVersion()),
                        String.valueOf(req.operatedAt())));
        StoredResponse response = idempotency.handle("GROUND_WIRE_REMOVE", req.requestId(), fingerprint,
                () -> toJson(service.remove(wireNo, req)));
        return respond(response);
    }

    /** 归还核销：仓管核对编号，仅核销已拆除实物。 */
    @PostMapping("/{wireNo}/return")
    public ResponseEntity<String> returnWire(@PathVariable String wireNo,
                                             @Valid @RequestBody ReturnRequest req) {
        String fingerprint = IdempotencyService.structuredFingerprint("GROUND_WIRE_RETURN", req.requestId(),
                List.of(wireNo, req.keeperId(), String.valueOf(req.expectedVersion()),
                        String.valueOf(req.operatedAt())));
        StoredResponse response = idempotency.handle("GROUND_WIRE_RETURN", req.requestId(), fingerprint,
                () -> toJson(service.returnWire(wireNo, req)));
        return respond(response);
    }

    /** 查询接地线列表，可按占用票过滤。 */
    @GetMapping
    public List<GroundWireView> list(@RequestParam(required = false) Long ticketId) {
        return service.list(ticketId);
    }

    /** 查询接地线详情：实物、证据与借用归还历史。 */
    @GetMapping("/{wireNo}")
    public GroundWireDetailView detail(@PathVariable String wireNo) {
        return service.detail(wireNo);
    }

    private StoredResponse toJson(Object dto) {
        try {
            return StoredResponse.ok(mapper.writeValueAsString(dto));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private ResponseEntity<String> respond(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
