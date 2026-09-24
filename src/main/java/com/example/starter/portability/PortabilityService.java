package com.example.starter.portability;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.portability.dto.ApplyRequest;
import com.example.starter.portability.dto.ErrorResponse;
import com.example.starter.portability.dto.EventResponse;
import com.example.starter.portability.dto.FlowResponse;
import com.example.starter.portability.dto.RequestIdRequest;
import com.example.starter.portability.dto.StoredResponse;
import com.example.starter.portability.model.FlowRecord;
import com.example.starter.portability.model.FlowStatus;
import com.example.starter.portability.model.SubscriberProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * 携号转网流转服务：申请、资格校验、签码、携出方确认、生效窗口广播与撤销。
 * 时间口径统一为 UTC、毫秒精度；授权码自签发起 24 小时有效，恰满即失效；
 * 生效窗口为每日 UTC 02:00（含）至 04:00（不含），恰在 04:00 整点视为窗口外。
 */
@Service
public class PortabilityService {

    /** 距上次携转的最短间隔。 */
    static final Duration MIN_PORT_INTERVAL = Duration.ofDays(120);
    /** 授权码有效期。 */
    static final Duration AUTH_CODE_TTL = Duration.ofHours(24);
    /** 生效窗口起点（UTC，含）。 */
    static final LocalTime WINDOW_START = LocalTime.of(2, 0);
    /** 生效窗口终点（UTC，不含）。 */
    static final LocalTime WINDOW_END = LocalTime.of(4, 0);

    private final PortabilityRepository repository;
    private final IdempotencyExecutor idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PortabilityService(PortabilityRepository repository,
                              IdempotencyExecutor idempotency,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 携号转网申请：资格校验（无欠费、无在网约定期限、距上次携转已满 120 日）通过后建立在途流程。
     */
    public StoredResponse apply(ApplyRequest request) {
        String fingerprint = IdempotencyExecutor.fingerprint("apply", request.requestId(), request.msisdn());
        return idempotency.execute(request.requestId(), "apply", fingerprint,
                () -> doApply(request));
    }

    /**
     * 签发授权码：自签发时刻起 24 小时有效（UTC）。
     */
    public StoredResponse issueAuthCode(long flowId, RequestIdRequest request) {
        String fingerprint = IdempotencyExecutor.fingerprint("auth-code", request.requestId(), String.valueOf(flowId));
        return idempotency.execute(request.requestId(), "auth-code", fingerprint,
                () -> doIssueAuthCode(flowId));
    }

    /**
     * 携出方确认：授权码须未失效；已生效流程的迟到确认不得改变结果。
     */
    public StoredResponse donorConfirm(long flowId, RequestIdRequest request) {
        String fingerprint = IdempotencyExecutor.fingerprint("donor-confirm", request.requestId(), String.valueOf(flowId));
        return idempotency.execute(request.requestId(), "donor-confirm", fingerprint,
                () -> doDonorConfirm(flowId));
    }

    /**
     * 生效广播：仅在每日 UTC 02:00-04:00 窗口内生效；窗口外拒绝并保留待生效状态。
     */
    public StoredResponse broadcast(long flowId, RequestIdRequest request) {
        String fingerprint = IdempotencyExecutor.fingerprint("broadcast", request.requestId(), String.valueOf(flowId));
        return idempotency.execute(request.requestId(), "broadcast", fingerprint,
                () -> doBroadcast(flowId));
    }

    /**
     * 用户撤销：生效前可撤销，撤销为终态；与生效并发时形成唯一终态。
     */
    public StoredResponse cancel(long flowId, RequestIdRequest request) {
        String fingerprint = IdempotencyExecutor.fingerprint("cancel", request.requestId(), String.valueOf(flowId));
        return idempotency.execute(request.requestId(), "cancel", fingerprint,
                () -> doCancel(flowId));
    }

    /**
     * 查询流程当前状态。
     */
    public FlowResponse getFlow(long flowId) {
        return FlowResponse.from(loadFlow(flowId));
    }

    /**
     * 按发生顺序查询流程事件。
     */
    public List<EventResponse> getEvents(long flowId) {
        loadFlow(flowId);
        return repository.findEventsByFlowId(flowId).stream().map(EventResponse::from).toList();
    }

    private IdempotencyExecutor.Outcome doApply(ApplyRequest request) {
        Instant now = now();
        if (repository.findActiveFlowByMsisdn(request.msisdn()).isPresent()) {
            return error(409, "FLOW_IN_FLIGHT", "同一号码已有在途流程，申请被拒绝", null);
        }
        List<String> reasons = eligibilityReasons(request.msisdn(), now);
        if (!reasons.isEmpty()) {
            return error(422, "ELIGIBILITY_FAILED", "资格校验未通过：" + String.join("；", reasons), null);
        }
        long flowId = repository.insertFlow(request.requestId(), request.msisdn(), now);
        repository.insertEvent(flowId, "APPLIED", "资格校验通过，流程建立在途", now);
        return ok(loadFlow(flowId));
    }

    private IdempotencyExecutor.Outcome doIssueAuthCode(long flowId) {
        FlowRecord flow = loadFlow(flowId);
        Instant now = now();
        switch (flow.status()) {
            case APPLIED -> {
                String authCode = generateAuthCode();
                Instant expiresAt = now.plus(AUTH_CODE_TTL);
                if (repository.markCodeIssued(flowId, authCode, now, expiresAt, now) == 0) {
                    return doIssueAuthCode(flowId);
                }
                repository.insertEvent(flowId, "AUTH_CODE_ISSUED",
                        "授权码已签发，有效期至 " + expiresAt, now);
                return ok(loadFlow(flowId));
            }
            case CODE_ISSUED -> {
                return ok(flow);
            }
            default -> {
                return error(409, "INVALID_STATE",
                        "当前状态 " + flow.status() + " 不允许签发授权码", flowId);
            }
        }
    }

    private IdempotencyExecutor.Outcome doDonorConfirm(long flowId) {
        FlowRecord flow = loadFlow(flowId);
        Instant now = now();
        switch (flow.status()) {
            case CODE_ISSUED -> {
                if (authCodeExpired(flow, now)) {
                    return error(422, "AUTH_CODE_EXPIRED", "授权码已失效（自签发起 24 小时有效）", flowId);
                }
                if (repository.markDonorConfirmed(flowId, now) == 0) {
                    return doDonorConfirm(flowId);
                }
                repository.insertEvent(flowId, "DONOR_CONFIRMED", "携出方已确认", now);
                return ok(loadFlow(flowId));
            }
            case DONOR_CONFIRMED -> {
                return ok(flow);
            }
            case ACTIVATED -> {
                return error(409, "FLOW_ALREADY_ACTIVATED",
                        "流程已生效，迟到的携出方确认不得改变结果", flowId);
            }
            default -> {
                return error(409, "INVALID_STATE",
                        "当前状态 " + flow.status() + " 不允许携出方确认", flowId);
            }
        }
    }

    private IdempotencyExecutor.Outcome doBroadcast(long flowId) {
        FlowRecord flow = loadFlow(flowId);
        Instant now = now();
        switch (flow.status()) {
            case DONOR_CONFIRMED -> {
                if (!inBroadcastWindow()) {
                    repository.insertEvent(flowId, "BROADCAST_DEFERRED",
                            "生效窗口外（UTC 02:00-04:00），保留待生效状态至下一窗口", now);
                    return error(422, "OUT_OF_WINDOW",
                            "当前不在生效窗口（UTC 02:00-04:00）内，流程保留待生效状态", flowId);
                }
                if (authCodeExpired(flow, now)) {
                    return error(422, "AUTH_CODE_EXPIRED", "授权码已失效（自签发起 24 小时有效）", flowId);
                }
                if (repository.markActivated(flowId, now) == 0) {
                    return doBroadcast(flowId);
                }
                repository.insertEvent(flowId, "ACTIVATED", "生效窗口内广播成功，授权码即时作废", now);
                return ok(loadFlow(flowId));
            }
            case ACTIVATED -> {
                return ok(flow);
            }
            case CANCELLED -> {
                return error(409, "FLOW_CANCELLED", "流程已撤销，不可再生效", flowId);
            }
            default -> {
                return error(409, "INVALID_STATE",
                        "当前状态 " + flow.status() + " 不允许生效广播", flowId);
            }
        }
    }

    private IdempotencyExecutor.Outcome doCancel(long flowId) {
        FlowRecord flow = loadFlow(flowId);
        Instant now = now();
        switch (flow.status()) {
            case ACTIVATED -> {
                return error(409, "FLOW_ALREADY_ACTIVATED", "流程已生效，不可撤销", flowId);
            }
            case CANCELLED -> {
                return ok(flow);
            }
            default -> {
                if (repository.markCancelled(flowId, now) == 0) {
                    return doCancel(flowId);
                }
                repository.insertEvent(flowId, "CANCELLED", "用户在生效前撤销，流程终止", now);
                return ok(loadFlow(flowId));
            }
        }
    }

    private List<String> eligibilityReasons(String msisdn, Instant now) {
        List<String> reasons = new ArrayList<>();
        SubscriberProfile profile = repository.findProfile(msisdn).orElse(null);
        if (profile == null) {
            reasons.add("号码档案不存在");
            return reasons;
        }
        if (profile.hasArrears()) {
            reasons.add("存在欠费");
        }
        if (profile.inContract()) {
            reasons.add("在网约定期限未届满");
        }
        if (profile.lastPortAt() != null
                && profile.lastPortAt().plus(MIN_PORT_INTERVAL).isAfter(now)) {
            reasons.add("距上次携转未满 120 日");
        }
        return reasons;
    }

    private boolean authCodeExpired(FlowRecord flow, Instant now) {
        // 恰满 24 小时即失效：now >= expiresAt 视为失效
        return flow.authCodeExpiresAt() == null || !now.isBefore(flow.authCodeExpiresAt());
    }

    private boolean inBroadcastWindow() {
        // 窗口为 [02:00, 04:00)：恰在 04:00 整点视为窗口外
        LocalTime time = LocalTime.now(clock);
        return !time.isBefore(WINDOW_START) && time.isBefore(WINDOW_END);
    }

    private FlowRecord loadFlow(long flowId) {
        return repository.findFlowById(flowId)
                .orElseThrow(() -> new BizException(404, "FLOW_NOT_FOUND", "流程不存在：" + flowId));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    private String generateAuthCode() {
        return "%08d".formatted(random.nextInt(100_000_000));
    }

    private IdempotencyExecutor.Outcome ok(FlowRecord flow) {
        return new IdempotencyExecutor.Outcome(
                new StoredResponse(200, toJson(FlowResponse.from(flow))), flow.id());
    }

    private IdempotencyExecutor.Outcome error(int httpStatus, String code, String message, Long flowId) {
        return new IdempotencyExecutor.Outcome(
                new StoredResponse(httpStatus, toJson(new ErrorResponse(code, message))), flowId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
