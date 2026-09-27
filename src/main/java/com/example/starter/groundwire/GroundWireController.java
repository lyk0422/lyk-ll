package com.example.starter.groundwire;

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

import com.example.starter.groundwire.GroundWireDtos.ApplyRequest;
import com.example.starter.groundwire.GroundWireDtos.BorrowRecordView;
import com.example.starter.groundwire.GroundWireDtos.BorrowRequest;
import com.example.starter.groundwire.GroundWireDtos.BorrowView;
import com.example.starter.groundwire.GroundWireDtos.OperationView;
import com.example.starter.groundwire.GroundWireDtos.RemoveRequest;
import com.example.starter.groundwire.GroundWireDtos.ReturnRequest;
import com.example.starter.groundwire.GroundWireDtos.ReturnView;
import com.example.starter.groundwire.GroundWireDtos.WireVersionItem;
import com.example.starter.idem.IdempotencyService;
import com.example.starter.idem.StoredResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 接地线实物联锁接口：借用、挂接、拆除、归还及台账/证据查询。
 * 四个写操作均以 requestId 幂等，指纹结构化覆盖版本、集合与精确时刻；
 * 业务变更、版本递增与幂等登记在同一事务内完成，失败不占键。
 */
@RestController
@RequestMapping("/api/tickets/{ticketId}")
public class GroundWireController {

    private static final String EP_BORROW = "GROUND_WIRE_BORROW";
    private static final String EP_APPLY = "GROUND_WIRE_APPLY";
    private static final String EP_REMOVE = "GROUND_WIRE_REMOVE";
    private static final String EP_RETURN = "GROUND_WIRE_RETURN";

    private final GroundWireService service;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public GroundWireController(GroundWireService service, IdempotencyService idempotency, ObjectMapper mapper) {
        this.service = service;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    /** 借用：一次可借多条，任一不可用整批拒绝。 */
    @PostMapping("/ground-wires/borrow")
    public ResponseEntity<String> borrow(@PathVariable long ticketId, @Valid @RequestBody BorrowRequest req) {
        List<String> fields = new java.util.ArrayList<>(List.of(
                String.valueOf(ticketId), req.intervalId(), itemsSegment(req.items()), req.occurredAt().toString()));
        StoredResponse response = idempotency.handle(EP_BORROW, req.requestId(),
                IdempotencyService.structuredFingerprint(EP_BORROW, fields),
                () -> toJson(service.borrow(ticketId, req)));
        return respond(response);
    }

    /** 挂接：引用本票步骤，双人确认，保存不可覆盖证据。 */
    @PostMapping("/ground-wires/{wireNo}/apply")
    public ResponseEntity<String> apply(@PathVariable long ticketId, @PathVariable String wireNo,
                                        @Valid @RequestBody ApplyRequest req) {
        List<String> fields = List.of(String.valueOf(ticketId), wireNo, String.valueOf(req.expectedVersion()),
                String.valueOf(req.stepSeq()), req.operatorId(), req.guardianId(), req.occurredAt().toString());
        StoredResponse response = idempotency.handle(EP_APPLY, req.requestId(),
                IdempotencyService.structuredFingerprint(EP_APPLY, fields),
                () -> toJson(service.apply(ticketId, wireNo, req)));
        return respond(response);
    }

    /** 拆除：双人确认，保存不可覆盖证据；票作废或异常后仍须合法拆除。 */
    @PostMapping("/ground-wires/{wireNo}/remove")
    public ResponseEntity<String> remove(@PathVariable long ticketId, @PathVariable String wireNo,
                                         @Valid @RequestBody RemoveRequest req) {
        List<String> fields = List.of(String.valueOf(ticketId), wireNo, String.valueOf(req.expectedVersion()),
                String.valueOf(req.stepSeq()), req.operatorId(), req.guardianId(), req.occurredAt().toString());
        StoredResponse response = idempotency.handle(EP_REMOVE, req.requestId(),
                IdempotencyService.structuredFingerprint(EP_REMOVE, fields),
                () -> toJson(service.remove(ticketId, wireNo, req)));
        return respond(response);
    }

    /** 归还：仓管核对编号，只核销已拆除实物。 */
    @PostMapping("/ground-wires/return")
    public ResponseEntity<String> returnWires(@PathVariable long ticketId, @Valid @RequestBody ReturnRequest req) {
        List<String> fields = List.of(String.valueOf(ticketId), itemsSegment(req.items()),
                req.warehouseKeeperId(), req.occurredAt().toString());
        StoredResponse response = idempotency.handle(EP_RETURN, req.requestId(),
                IdempotencyService.structuredFingerprint(EP_RETURN, fields),
                () -> toJson(service.returnWires(ticketId, req)));
        return respond(response);
    }

    /** 查询本票借用/核销登记（历史保留）。 */
    @GetMapping("/ground-wires/borrows")
    public List<BorrowRecordView> borrows(@PathVariable long ticketId) {
        return service.listBorrows(ticketId);
    }

    /** 查询本票挂接/拆除操作证据（只追加不可覆盖）。 */
    @GetMapping("/ground-wires/operations")
    public List<OperationView> operations(@PathVariable long ticketId) {
        return service.listOperations(ticketId);
    }

    /**
     * 集合的结构化编码：元素个数前缀 + 每条编号/版本均做长度前缀，
     * 编号含分隔符也无法改变集合边界。
     */
    private static String itemsSegment(List<WireVersionItem> items) {
        StringBuilder sb = new StringBuilder("n=").append(items.size());
        for (WireVersionItem item : items) {
            sb.append(";w=").append(item.wireNo().length()).append(':').append(item.wireNo())
                    .append(",v=").append(item.expectedVersion());
        }
        return sb.toString();
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
