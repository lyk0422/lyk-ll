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

import com.example.starter.continuation.ContinuationDtos.ApplyContinuationRequest;
import com.example.starter.continuation.ContinuationDtos.ContinuationView;
import com.example.starter.continuation.ContinuationDtos.EffectContinuationRequest;
import com.example.starter.continuation.ContinuationDtos.ReviewContinuationRequest;
import com.example.starter.continuation.ContinuationDtos.WithdrawContinuationRequest;
import com.example.starter.idem.IdempotencyService;
import com.example.starter.idem.StoredResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 余步续票接口：申请、双人审核、生效、撤回与查询。
 * 所有写操作以 requestId 幂等：结构化指纹覆盖全部业务字段，
 * 同键同参重放首次冻结结果，异参 409，失败不占键。
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

    /** 申请续票：冻结原票间隔、步序与剩余步骤。 */
    @PostMapping
    public ResponseEntity<String> apply(@Valid @RequestBody ApplyContinuationRequest req) {
        List<String> steps = req.steps().stream()
                .map(s -> s.operationType() + ":" + s.deviceId() + ":" + s.expectedState())
                .toList();
        String fingerprint = IdempotencyService.fingerprintStructured("CONTINUATION_APPLY", req.requestId(),
                List.of(req.originalTicketId(), req.intervalId(), req.title(), steps));
        StoredResponse response = idempotency.handle("CONTINUATION_APPLY", req.requestId(), fingerprint,
                () -> toJson(service.apply(req)));
        return respond(response);
    }

    /** 审核确认：两名不同审核人各确认一次。 */
    @PostMapping("/{id}/review")
    public ResponseEntity<String> review(@PathVariable long id, @Valid @RequestBody ReviewContinuationRequest req) {
        String fingerprint = IdempotencyService.fingerprintStructured("CONTINUATION_REVIEW", req.requestId(),
                List.of(id, req.expectedVersion(), req.reviewerId()));
        StoredResponse response = idempotency.handle("CONTINUATION_REVIEW", req.requestId(), fingerprint,
                () -> toJson(service.review(id, req)));
        return respond(response);
    }

    /** 生效：复核原票版本与当前设备状态，原子转移间隔执行权与接地线责任。 */
    @PostMapping("/{id}/effect")
    public ResponseEntity<String> effect(@PathVariable long id, @Valid @RequestBody EffectContinuationRequest req) {
        String fingerprint = IdempotencyService.fingerprintStructured("CONTINUATION_EFFECT", req.requestId(),
                List.of(id, req.expectedVersion()));
        StoredResponse response = idempotency.handle("CONTINUATION_EFFECT", req.requestId(), fingerprint,
                () -> toJson(service.effect(id, req)));
        return respond(response);
    }

    /** 撤回：仅未生效申请可撤回，不释放原间隔或实物。 */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<String> withdraw(@PathVariable long id,
                                           @Valid @RequestBody WithdrawContinuationRequest req) {
        String fingerprint = IdempotencyService.fingerprintStructured("CONTINUATION_WITHDRAW", req.requestId(),
                List.of(id, req.expectedVersion()));
        StoredResponse response = idempotency.handle("CONTINUATION_WITHDRAW", req.requestId(), fingerprint,
                () -> toJson(service.withdraw(id, req)));
        return respond(response);
    }

    /** 查询续票单据。 */
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
