package com.example.starter.ground;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.ground.GroundWireDtos.AttachWireRequest;
import com.example.starter.ground.GroundWireDtos.BorrowWiresRequest;
import com.example.starter.ground.GroundWireDtos.DetachWireRequest;
import com.example.starter.ground.GroundWireDtos.ReturnWiresRequest;
import com.example.starter.ground.GroundWireDtos.TicketGroundView;
import com.example.starter.ground.GroundWireDtos.WireDetailView;
import com.example.starter.ground.GroundWireDtos.WireView;
import com.example.starter.idem.IdempotencyService;
import com.example.starter.idem.StoredResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 实物接地线接口：借用、归还、挂接、拆除与查询。
 * 借还与竞争票借用并发不能双占；挂接与送电并发不得既通过送电又留下挂接。
 * 所有写操作以 requestId 幂等：同键同参重放首次冻结结果，异参 409，失败不占键。
 */
@RestController
@RequestMapping("/api/ground-wires")
public class GroundWireController {

    private final GroundWireService service;
    private final IdempotencyService idempotency;
    final ObjectMapper mapper;

    public GroundWireController(GroundWireService service, IdempotencyService idempotency, ObjectMapper mapper) {
        this.service = service;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    /** 批量借用：绑定未归档且未作废的操作票及间隔，一次可借多条。 */
    @PostMapping("/borrow")
    public ResponseEntity<String> borrow(@Valid @RequestBody BorrowWiresRequest req) {
        List<String> codes = req.wireCodes().stream().sorted().toList();
        String fingerprint = IdempotencyService.fingerprintStructured("GROUND_BORROW", req.requestId(), List.of(
                req.ticketId(), req.expectedVersion(), codes, req.occurredAt()));
        StoredResponse response = idempotency.handle("GROUND_BORROW", req.requestId(), fingerprint,
                () -> toJson(service.borrow(req.ticketId(), req)));
        return respond(response);
    }

    /** 批量归还：仓管核对编号，仅核销已拆除待归还实物。 */
    @PostMapping("/return")
    public ResponseEntity<String> returnWires(@Valid @RequestBody ReturnWiresRequest req) {
        List<String> codes = req.wireCodes().stream().sorted().toList();
        String fingerprint = IdempotencyService.fingerprintStructured("GROUND_RETURN", req.requestId(), List.of(
                req.ticketId(), req.expectedVersion(), codes, req.keeperId(), req.occurredAt()));
        StoredResponse response = idempotency.handle("GROUND_RETURN", req.requestId(), fingerprint,
                () -> toJson(service.returnWires(req.ticketId(), req)));
        return respond(response);
    }

    /** 挂接：引用本票 APPLY_GROUND 步骤，双人确认。 */
    @PostMapping("/attach")
    public ResponseEntity<String> attach(@Valid @RequestBody AttachWireRequest req) {
        String fingerprint = IdempotencyService.fingerprintStructured("GROUND_ATTACH", req.requestId(), List.of(
                req.ticketId(), req.expectedVersion(), req.stepSeq(), req.wireCode(),
                req.operatorId(), req.guardianId(), req.occurredAt()));
        StoredResponse response = idempotency.handle("GROUND_ATTACH", req.requestId(), fingerprint,
                () -> toJson(service.attach(req.ticketId(), req)));
        return respond(response);
    }

    /** 拆除：引用本票 REMOVE_GROUND 步骤，双人确认，证据不可覆盖。 */
    @PostMapping("/detach")
    public ResponseEntity<String> detach(@Valid @RequestBody DetachWireRequest req) {
        String fingerprint = IdempotencyService.fingerprintStructured("GROUND_DETACH", req.requestId(), List.of(
                req.ticketId(), req.expectedVersion(), req.stepSeq(), req.wireCode(),
                req.operatorId(), req.guardianId(), req.occurredAt()));
        StoredResponse response = idempotency.handle("GROUND_DETACH", req.requestId(), fingerprint,
                () -> toJson(service.detach(req.ticketId(), req)));
        return respond(response);
    }

    /** 查询单条实物明细（台账、借用历史、操作证据）。 */
    @GetMapping("/{code}")
    public WireDetailView getWire(@PathVariable String code) {
        return service.getWire(code);
    }

    /** 查询全部实物台账。 */
    @GetMapping
    public List<WireView> listWires() {
        return service.listWires();
    }

    /** 查询一张票关联的借用记录与操作证据（含已归还历史）。 */
    @GetMapping("/tickets/{ticketId}")
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
