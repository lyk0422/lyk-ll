package com.example.starter.ground;

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
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.ground.GroundWireDtos.AttachRequest;
import com.example.starter.ground.GroundWireDtos.BorrowRequest;
import com.example.starter.ground.GroundWireDtos.DetachRequest;
import com.example.starter.ground.GroundWireDtos.ReturnRequest;
import com.example.starter.ground.GroundWireDtos.TicketGroundView;
import com.example.starter.ground.GroundWireDtos.WireDetailView;
import com.example.starter.ground.GroundWireDtos.WireItem;
import com.example.starter.ground.GroundWireDtos.WireView;
import com.example.starter.idem.IdempotencyService;
import com.example.starter.idem.StoredResponse;

/**
 * 临时接地线接口：借用、挂接、拆除、归还核销与查询。
 * 写操作均以 requestId 幂等，指纹结构化覆盖版本、集合与精确时刻。
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

    /** 借用：绑定未归档且未作废的操作票，任一不可用整批拒绝。 */
    @PostMapping("/borrow")
    public ResponseEntity<String> borrow(@Valid @RequestBody BorrowRequest req) {
        List<String> fields = new ArrayList<>();
        fields.add(String.valueOf(req.ticketId()));
        fields.add(req.occurredAt().toString());
        fields.add(String.valueOf(req.items().size()));
        for (WireItem item : req.items()) {
            fields.add(item.wireNo());
            fields.add(String.valueOf(item.expectedVersion()));
        }
        String fingerprint = IdempotencyService.structuredFingerprint("GROUND_BORROW", req.requestId(), fields);
        StoredResponse response = idempotency.handle("GROUND_BORROW", req.requestId(), fingerprint,
                () -> toJson(service.borrow(req)));
        return respond(response);
    }

    /** 挂接：引用本票步骤，双人确认。 */
    @PostMapping("/attach")
    public ResponseEntity<String> attach(@Valid @RequestBody AttachRequest req) {
        String fingerprint = IdempotencyService.structuredFingerprint("GROUND_ATTACH", req.requestId(),
                List.of(String.valueOf(req.ticketId()), req.wireNo(), String.valueOf(req.stepSeq()),
                        req.operatorId(), req.guardianId(), String.valueOf(req.expectedVersion()),
                        req.occurredAt().toString()));
        StoredResponse response = idempotency.handle("GROUND_ATTACH", req.requestId(), fingerprint,
                () -> toJson(service.attach(req)));
        return respond(response);
    }

    /** 拆除：双人确认。 */
    @PostMapping("/detach")
    public ResponseEntity<String> detach(@Valid @RequestBody DetachRequest req) {
        String fingerprint = IdempotencyService.structuredFingerprint("GROUND_DETACH", req.requestId(),
                List.of(String.valueOf(req.ticketId()), req.wireNo(), req.operatorId(), req.guardianId(),
                        String.valueOf(req.expectedVersion()), req.occurredAt().toString()));
        StoredResponse response = idempotency.handle("GROUND_DETACH", req.requestId(), fingerprint,
                () -> toJson(service.detach(req)));
        return respond(response);
    }

    /** 归还核销：仓管核对编号，只核销已拆除实物。 */
    @PostMapping("/return")
    public ResponseEntity<String> returnWires(@Valid @RequestBody ReturnRequest req) {
        List<String> fields = new ArrayList<>();
        fields.add(String.valueOf(req.ticketId()));
        fields.add(req.keeperId());
        fields.add(req.occurredAt().toString());
        fields.add(String.valueOf(req.items().size()));
        for (WireItem item : req.items()) {
            fields.add(item.wireNo());
            fields.add(String.valueOf(item.expectedVersion()));
        }
        String fingerprint = IdempotencyService.structuredFingerprint("GROUND_RETURN", req.requestId(), fields);
        StoredResponse response = idempotency.handle("GROUND_RETURN", req.requestId(), fingerprint,
                () -> toJson(service.returnWires(req)));
        return respond(response);
    }

    /** 查询全部接地线台账。 */
    @GetMapping
    public List<WireView> list() {
        return service.listWires();
    }

    /** 查询接地线详情与全部操作证据。 */
    @GetMapping("/{wireNo}")
    public WireDetailView detail(@PathVariable String wireNo) {
        return service.wireDetail(wireNo);
    }

    /** 查询操作票维度接地线：当前持有与历史证据。 */
    @GetMapping("/ticket/{ticketId}")
    public TicketGroundView ticketGround(@PathVariable long ticketId) {
        return service.ticketGround(ticketId);
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
