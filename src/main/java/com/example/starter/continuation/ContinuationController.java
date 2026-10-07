package com.example.starter.continuation;

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

import com.example.starter.continuation.ContinuationDtos.ApplyRequest;
import com.example.starter.continuation.ContinuationDtos.ConfirmRequest;
import com.example.starter.continuation.ContinuationDtos.ContinuationView;
import com.example.starter.continuation.ContinuationDtos.IssueRequest;
import com.example.starter.continuation.ContinuationDtos.WithdrawRequest;
import com.example.starter.idem.IdempotencyService;
import com.example.starter.idem.StoredResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 余步续票接口：申请、双人确认、签发、撤回与查询。
 * 全部写操作以 requestId 幂等：同键同参重放首次冻结结果，异参 409，失败不占键；
 * 申请单版本从1起，修改携带 expectedVersion，成功加1，旧版 409。
 */
@RestController
@RequestMapping("/api/continuations")
public class ContinuationController {

    private final ContinuationService service;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public ContinuationController(ContinuationService service, IdempotencyService idempotency, ObjectMapper mapper) {
        this.service = service;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    /** 申请续票：引用异常/作废未归档原票，冻结剩余步骤。 */
    @PostMapping
    public ResponseEntity<String> apply(@Valid @RequestBody ApplyRequest req) {
        List<Object> steps = req.remainingSteps() == null ? List.of()
                : req.remainingSteps().stream()
                        .map(s -> (Object) (s.seq() + ":" + s.operationType() + ":" + s.deviceId()
                                + ":" + s.expectedState()))
                        .toList();
        String fingerprint = IdempotencyService.fingerprintStructured("CONTINUATION_APPLY", req.requestId(), List.of(
                req.sourceTicketId(), req.expectedSourceVersion(), req.intervalId(), req.title(), steps));
        StoredResponse response = idempotency.handle("CONTINUATION_APPLY", req.requestId(), fingerprint,
                () -> toJson(service.apply(req)));
        return respond(response);
    }

    /** 双人确认：两名不同审核人。 */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<String> confirm(@PathVariable long id, @Valid @RequestBody ConfirmRequest req) {
        String fingerprint = IdempotencyService.fingerprintStructured("CONTINUATION_CONFIRM", req.requestId(),
                List.of(id, req.expectedVersion(), req.reviewerA(), req.reviewerB()));
        StoredResponse response = idempotency.handle("CONTINUATION_CONFIRM", req.requestId(), fingerprint,
                () -> toJson(service.confirm(id, req)));
        return respond(response);
    }

    /** 签发生效：复核当前设备状态与原票版本，原子交接间隔执行权与实物责任。 */
    @PostMapping("/{id}/issue")
    public ResponseEntity<String> issue(@PathVariable long id, @Valid @RequestBody IssueRequest req) {
        String fingerprint = IdempotencyService.fingerprintStructured("CONTINUATION_ISSUE", req.requestId(),
                List.of(id, req.expectedVersion()));
        StoredResponse response = idempotency.handle("CONTINUATION_ISSUE", req.requestId(), fingerprint,
                () -> toJson(service.issue(id, req)));
        return respond(response);
    }

    /** 撤回：仅未生效申请可撤回。 */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<String> withdraw(@PathVariable long id, @Valid @RequestBody WithdrawRequest req) {
        String fingerprint = IdempotencyService.fingerprintStructured("CONTINUATION_WITHDRAW", req.requestId(),
                List.of(id, req.expectedVersion()));
        StoredResponse response = idempotency.handle("CONTINUATION_WITHDRAW", req.requestId(), fingerprint,
                () -> toJson(service.withdraw(id, req)));
        return respond(response);
    }

    /** 查询续票申请单。 */
    @GetMapping("/{id}")
    public ContinuationView get(@PathVariable long id) {
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
