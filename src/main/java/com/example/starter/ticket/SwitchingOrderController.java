package com.example.starter.ticket;

import java.util.stream.Collectors;

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

import com.example.starter.idem.IdempotencyService;
import com.example.starter.idem.StoredResponse;
import com.example.starter.ticket.TicketDtos.ArchiveRequest;
import com.example.starter.ticket.TicketDtos.ConfirmStepRequest;
import com.example.starter.ticket.TicketDtos.CreateTicketRequest;
import com.example.starter.ticket.TicketDtos.IssueRequest;
import com.example.starter.ticket.TicketDtos.ReviewRequest;

/**
 * 倒闸操作票接口：拟票、审核、预发、逐项确认、归档与查询。
 * 拟票、预发与步确认均以 requestId 幂等。
 */
@RestController
@RequestMapping("/api/tickets")
public class SwitchingOrderController {

    private final SwitchingOrderService service;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public SwitchingOrderController(SwitchingOrderService service, IdempotencyService idempotency,
                                    ObjectMapper mapper) {
        this.service = service;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    /** 拟票。 */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateTicketRequest req) {
        String stepsFingerprint = req.steps().stream()
                .map(s -> s.operationType() + ":" + s.deviceId() + ":" + s.expectedState())
                .collect(Collectors.joining(";"));
        String fingerprint = IdempotencyService.fingerprint("TICKET_CREATE", req.requestId(),
                req.intervalId(), req.title(), stepsFingerprint);
        StoredResponse response = idempotency.handle("TICKET_CREATE", req.requestId(), fingerprint,
                () -> toJson(service.create(req)));
        return respond(response);
    }

    /** 审核。 */
    @PostMapping("/{id}/review")
    public ResponseEntity<String> review(@PathVariable long id, @Valid @RequestBody ReviewRequest req) {
        String fingerprint = IdempotencyService.fingerprint("TICKET_REVIEW", req.requestId(),
                String.valueOf(id), req.reviewerId());
        StoredResponse response = idempotency.handle("TICKET_REVIEW", req.requestId(), fingerprint,
                () -> toJson(service.review(id, req)));
        return respond(response);
    }

    /** 预发：同一间隔同时刻仅允许一张执行中操作票。 */
    @PostMapping("/{id}/issue")
    public ResponseEntity<String> issue(@PathVariable long id, @Valid @RequestBody IssueRequest req) {
        String fingerprint = IdempotencyService.fingerprint("TICKET_ISSUE", req.requestId(), String.valueOf(id));
        StoredResponse response = idempotency.handle("TICKET_ISSUE", req.requestId(), fingerprint,
                () -> toJson(service.issue(id, req)));
        return respond(response);
    }

    /** 逐项执行：步确认须操作人、监护人双确认。 */
    @PostMapping("/{id}/steps/{seq}/confirm")
    public ResponseEntity<String> confirm(@PathVariable long id, @PathVariable int seq,
                                          @Valid @RequestBody ConfirmStepRequest req) {
        String fingerprint = IdempotencyService.fingerprint("STEP_CONFIRM", req.requestId(),
                String.valueOf(id), String.valueOf(seq), req.operatorId(), req.guardianId());
        StoredResponse response = idempotency.handle("STEP_CONFIRM", req.requestId(), fingerprint,
                () -> toJson(service.confirm(id, seq, req)));
        return respond(response);
    }

    /** 归档：终态，重复归档返回幂等成功。 */
    @PostMapping("/{id}/archive")
    public ResponseEntity<String> archive(@PathVariable long id, @Valid @RequestBody ArchiveRequest req) {
        String fingerprint = IdempotencyService.fingerprint("TICKET_ARCHIVE", req.requestId(), String.valueOf(id));
        StoredResponse response = idempotency.handle("TICKET_ARCHIVE", req.requestId(), fingerprint,
                () -> toJson(service.archive(id, req)));
        return respond(response);
    }

    /** 查询操作票详情。 */
    @GetMapping("/{id}")
    public TicketDtos.TicketDetailView get(@PathVariable long id) {
        return service.get(id);
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
