package com.example.starter.portability.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.portability.BusinessException;
import com.example.starter.portability.FlowState;
import com.example.starter.portability.IdempotencyRecord;
import com.example.starter.portability.PortabilityFlow;
import com.example.starter.portability.repo.EffectiveRecordRepository;
import com.example.starter.portability.repo.FlowEventRepository;
import com.example.starter.portability.repo.IdempotencyRepository;
import com.example.starter.portability.repo.PortabilityFlowRepository;
import com.example.starter.portability.web.ApplyRequest;
import com.example.starter.portability.web.BroadcastRequest;
import com.example.starter.portability.web.BroadcastView;
import com.example.starter.portability.web.ConfirmRequest;
import com.example.starter.portability.web.EventView;
import com.example.starter.portability.web.FlowView;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 携号转网流转核心服务：申请（资格校验+签码）、携出方确认、生效窗口广播、撤销与事件查询。
 * 申请与生效广播按 requestId 幂等；同一号码任一时刻仅允许一个在途流程。
 */
@Service
public class PortabilityService {

    /** 授权码有效期：自签发时刻起 24 小时（UTC），恰满即失效。 */
    static final long AUTH_CODE_TTL_HOURS = 24;
    /** 距上次携转的最小间隔：120 日，恰满 120 日视为满足。 */
    static final long MIN_PORT_INTERVAL_DAYS = 120;
    /** 每日生效窗口 [02:00, 04:00)，04:00 整点视为窗口外。 */
    static final LocalTime WINDOW_START = LocalTime.of(2, 0);
    static final LocalTime WINDOW_END = LocalTime.of(4, 0);

    private static final String ENDPOINT_APPLY = "APPLY";
    private static final String ENDPOINT_BROADCAST = "BROADCAST";

    private final PortabilityFlowRepository flowRepo;
    private final FlowEventRepository eventRepo;
    private final EffectiveRecordRepository effectiveRepo;
    private final IdempotencyRepository idempotencyRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PortabilityService(PortabilityFlowRepository flowRepo,
                              FlowEventRepository eventRepo,
                              EffectiveRecordRepository effectiveRepo,
                              IdempotencyRepository idempotencyRepo,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.flowRepo = flowRepo;
        this.eventRepo = eventRepo;
        this.effectiveRepo = effectiveRepo;
        this.idempotencyRepo = idempotencyRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 申请携转：幂等校验 → 资格校验 → 签发授权码并建立在途流程。
     * 同号并发申请由 active_msisdn 唯一约束保证仅一个进入在途。
     */
    @Transactional
    public FlowView apply(ApplyRequest request) {
        String fingerprint = fingerprint(ENDPOINT_APPLY, request.requestId(), request.msisdn(),
                request.donorOperator(), request.recipientOperator(),
                String.valueOf(request.hasUnpaidBill()),
                String.valueOf(request.contractEndDate()),
                String.valueOf(request.lastPortedAt()));
        Optional<IdempotencyRecord> replay = idempotencyRepo.find(ENDPOINT_APPLY, request.requestId());
        if (replay.isPresent()) {
            return replayResponse(replay.get(), fingerprint, FlowView.class);
        }

        Instant now = Instant.now(clock);
        checkEligibility(request, now);

        Instant expiresAt = now.plus(AUTH_CODE_TTL_HOURS, ChronoUnit.HOURS);
        PortabilityFlow flow = new PortabilityFlow(null, request.requestId(), request.msisdn(),
                request.donorOperator(), request.recipientOperator(), FlowState.AUTH_ISSUED,
                generateAuthCode(), now, expiresAt, now, now);
        try {
            flow = flowRepo.insert(flow, request.msisdn());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "NUMBER_IN_FLIGHT",
                    "号码 " + request.msisdn() + " 已存在在途携转流程");
        }
        eventRepo.insert(flow.id(), "FLOW_APPLIED", "携转申请受理", now);
        eventRepo.insert(flow.id(), "AUTH_CODE_ISSUED", "授权码签发，24 小时有效（UTC）", now);

        FlowView view = FlowView.from(flow);
        idempotencyRepo.insert(ENDPOINT_APPLY, request.requestId(), fingerprint,
                HttpStatus.CREATED.value(), writeJson(view), flow.id(), now);
        return view;
    }

    /**
     * 携出方确认：要求流程处于已签码状态、授权码匹配且未过期；终态后的迟到确认一律拒绝。
     */
    @Transactional
    public FlowView confirm(long flowId, ConfirmRequest request) {
        PortabilityFlow flow = requireFlowForUpdate(flowId);
        if (flow.state().isTerminal()) {
            throw new BusinessException(HttpStatus.CONFLICT, "FLOW_TERMINATED",
                    "流程已进入终态 " + flow.state() + "，确认不再生效");
        }
        if (flow.state() != FlowState.AUTH_ISSUED) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE",
                    "当前状态 " + flow.state() + " 不允许确认");
        }
        if (!flow.authCode().equals(request.authCode())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "AUTH_CODE_MISMATCH",
                    "授权码不匹配");
        }
        Instant now = Instant.now(clock);
        if (!now.isBefore(flow.authExpiresAt())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "AUTH_CODE_EXPIRED",
                    "授权码已失效（恰满 24 小时即失效）");
        }
        transition(flow.id(), FlowState.CONFIRMED, now, FlowState.AUTH_ISSUED);
        eventRepo.insert(flow.id(), "DONOR_CONFIRMED", "携出方确认", now);
        return FlowView.from(requireFlow(flowId));
    }

    /**
     * 生效广播：仅在每日 02:00-04:00 窗口内生效；04:00 整点视为窗口外，保留待生效状态。
     * 按 requestId 幂等；同一流程仅产生一条生效记录；成功后授权码立即作废。
     */
    @Transactional
    public BroadcastView broadcast(long flowId, BroadcastRequest request) {
        String fingerprint = fingerprint(ENDPOINT_BROADCAST, request.requestId(),
                String.valueOf(flowId));
        Optional<IdempotencyRecord> replay = idempotencyRepo.find(ENDPOINT_BROADCAST, request.requestId());
        if (replay.isPresent()) {
            return replayResponse(replay.get(), fingerprint, BroadcastView.class);
        }

        PortabilityFlow flow = requireFlowForUpdate(flowId);
        if (flow.state() == FlowState.EFFECTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALREADY_EFFECTIVE",
                    "流程已生效，重复广播请使用原 requestId");
        }
        if (flow.state() == FlowState.CANCELLED) {
            throw new BusinessException(HttpStatus.CONFLICT, "FLOW_CANCELLED",
                    "流程已撤销，不能生效");
        }
        if (flow.state() != FlowState.CONFIRMED) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE",
                    "当前状态 " + flow.state() + " 不允许生效广播，请先完成携出方确认");
        }
        LocalTime nowTime = LocalTime.now(clock);
        if (nowTime.isBefore(WINDOW_START) || !nowTime.isBefore(WINDOW_END)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTSIDE_EFFECTIVE_WINDOW",
                    "当前时刻 " + nowTime + " 不在每日 02:00-04:00 生效窗口内，流程保留待生效状态");
        }

        Instant now = Instant.now(clock);
        transition(flow.id(), FlowState.EFFECTIVE, now, FlowState.CONFIRMED);
        effectiveRepo.insert(flow.id(), flow.msisdn(), request.requestId(), now);
        eventRepo.insert(flow.id(), "BROADCAST_EFFECTIVE", "生效窗口内广播生效，原授权码作废", now);

        BroadcastView view = new BroadcastView(flow.id(), flow.msisdn(), FlowState.EFFECTIVE,
                request.requestId(), now);
        idempotencyRepo.insert(ENDPOINT_BROADCAST, request.requestId(), fingerprint,
                HttpStatus.OK.value(), writeJson(view), flow.id(), now);
        return view;
    }

    /**
     * 用户在生效前撤销；撤销为终态。与生效并发时由条件更新保证唯一终态。
     */
    @Transactional
    public FlowView cancel(long flowId) {
        PortabilityFlow flow = requireFlowForUpdate(flowId);
        if (flow.state() == FlowState.EFFECTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALREADY_EFFECTIVE",
                    "流程已生效，不能撤销");
        }
        if (flow.state() == FlowState.CANCELLED) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALREADY_CANCELLED",
                    "流程已撤销");
        }
        Instant now = Instant.now(clock);
        transition(flow.id(), FlowState.CANCELLED, now, FlowState.AUTH_ISSUED, FlowState.CONFIRMED);
        eventRepo.insert(flow.id(), "FLOW_CANCELLED", "用户撤销携转", now);
        return FlowView.from(requireFlow(flowId));
    }

    @Transactional(readOnly = true)
    public FlowView getFlow(long flowId) {
        return FlowView.from(requireFlow(flowId));
    }

    @Transactional(readOnly = true)
    public List<EventView> listEvents(long flowId) {
        requireFlow(flowId);
        return eventRepo.findByFlowIdOrderById(flowId).stream().map(EventView::from).toList();
    }

    private void checkEligibility(ApplyRequest request, Instant now) {
        if (Boolean.TRUE.equals(request.hasUnpaidBill())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ELIGIBILITY_UNPAID_BILL",
                    "资格校验失败：存在欠费");
        }
        if (request.contractEndDate() != null
                && request.contractEndDate().isAfter(LocalDate.now(clock))) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ELIGIBILITY_IN_CONTRACT",
                    "资格校验失败：仍在网约定期限内");
        }
        if (request.lastPortedAt() != null
                && now.isBefore(request.lastPortedAt().plus(MIN_PORT_INTERVAL_DAYS, ChronoUnit.DAYS))) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ELIGIBILITY_PORT_INTERVAL",
                    "资格校验失败：距上次携转未满 120 日");
        }
    }

    private void transition(long flowId, FlowState target, Instant now, FlowState... expected) {
        int updated = flowRepo.transitionState(flowId, target, now, expected);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "STATE_RACE",
                    "流程状态已并发变更，请查询最新状态");
        }
    }

    private PortabilityFlow requireFlow(long flowId) {
        return flowRepo.findById(flowId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "FLOW_NOT_FOUND",
                        "流程 " + flowId + " 不存在"));
    }

    private PortabilityFlow requireFlowForUpdate(long flowId) {
        return flowRepo.findByIdForUpdate(flowId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "FLOW_NOT_FOUND",
                        "流程 " + flowId + " 不存在"));
    }

    private <T> T replayResponse(IdempotencyRecord record, String fingerprint, Class<T> type) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "requestId " + record.requestId() + " 已使用且业务字段不一致");
        }
        try {
            return objectMapper.readValue(record.responseBody(), type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应快照反序列化失败", e);
        }
    }

    private String writeJson(Object view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (Exception e) {
            throw new IllegalStateException("响应快照序列化失败", e);
        }
    }

    private static String generateAuthCode() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
