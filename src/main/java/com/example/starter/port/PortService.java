package com.example.starter.port;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 携号转网流程服务：申请、签码、携出方确认、生效广播与撤销。
 *
 * <p>关键规则：
 * <ul>
 *   <li>资格：无欠费、无在网约定期限、距上次携转已满 120 日（恰满 120 日视为满足）；</li>
 *   <li>授权码自签发起 24 小时有效（UTC），恰满 24 小时即失效；</li>
 *   <li>生效窗口为每日 02:00（含）至 04:00（不含，UTC），04:00 整点视为窗口外；</li>
 *   <li>同一号码任一时刻只允许一个在途流程；申请与广播按 requestId 幂等，
 *       指纹覆盖 requestId 与全部业务字段，同键异参返回 409；</li>
 *   <li>撤销与生效通过带状态前置条件的 UPDATE 竞争，保证唯一终态。</li>
 * </ul>
 */
@Service
public class PortService {

    /** 两次携转的最小间隔 */
    static final Duration MIN_PORT_INTERVAL = Duration.ofDays(120);
    /** 授权码有效期 */
    static final Duration AUTH_CODE_TTL = Duration.ofHours(24);
    /** 生效窗口起点（UTC，含） */
    static final LocalTime WINDOW_START = LocalTime.of(2, 0);
    /** 生效窗口终点（UTC，不含；04:00 整点视为窗口外） */
    static final LocalTime WINDOW_END = LocalTime.of(4, 0);

    private final PortRepository portRepository;
    private final SubscriberRepository subscriberRepository;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PortService(PortRepository portRepository, SubscriberRepository subscriberRepository,
                       Clock clock) {
        this.portRepository = portRepository;
        this.subscriberRepository = subscriberRepository;
        this.clock = clock;
    }

    /**
     * 用户向携入方提交携转申请。资格校验与在途占位在同一事务内原子完成。
     */
    @Transactional
    public PortFlow apply(String requestId, String msisdn, String donorOperator,
                          String recipientOperator) {
        String fingerprint = fingerprint(requestId, msisdn, donorOperator, recipientOperator);
        var existing = portRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return resolveReplay(existing.get(), fingerprint);
        }

        Instant now = clock.instant();
        var subscriber = subscriberRepository.lockByMsisdn(msisdn)
                .orElseThrow(() -> BusinessException.notFound(
                        "SUBSCRIBER_NOT_FOUND", "号码不存在：" + msisdn));
        checkEligibility(subscriber, donorOperator, now);

        long flowId;
        try {
            flowId = portRepository.insertFlow(requestId, fingerprint, msisdn,
                    donorOperator, recipientOperator, now);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 已被其他事务提交：同参重放返回首次结果，异参返回 409
            return resolveReplay(portRepository.findByRequestId(requestId).orElseThrow(), fingerprint);
        }
        if (!portRepository.tryInsertInflight(msisdn, flowId, now)) {
            throw BusinessException.conflict("IN_FLIGHT", "号码已存在在途携转流程：" + msisdn);
        }
        portRepository.insertEvent(flowId, "APPLIED",
                "申请携入 " + recipientOperator + "，携出 " + donorOperator, now);
        return portRepository.findById(flowId).orElseThrow();
    }

    /**
     * 签发授权码：自签发时刻起 24 小时有效（UTC）。已签发时重复调用返回原授权码。
     */
    @Transactional
    public PortFlow issueAuthCode(long flowId) {
        PortFlow flow = getFlow(flowId);
        if (flow.status() == PortStatus.CODE_ISSUED) {
            return flow;
        }
        if (flow.status() != PortStatus.APPLIED) {
            throw BusinessException.conflict("INVALID_STATE",
                    "当前状态不允许签发授权码：" + flow.status());
        }
        Instant now = clock.instant();
        String authCode = String.format("%08d", random.nextInt(100_000_000));
        if (portRepository.issueAuthCode(flowId, authCode, now, now.plus(AUTH_CODE_TTL), now) == 0) {
            throw BusinessException.conflict("STATE_RACE", "流程状态已变化，请刷新后重试");
        }
        portRepository.insertEvent(flowId, "AUTH_CODE_ISSUED", "授权码已签发，24 小时内有效", now);
        return portRepository.findById(flowId).orElseThrow();
    }

    /**
     * 携出方确认：要求授权码匹配且未过期；流程生效后迟到的确认不改变结果。
     */
    @Transactional
    public PortFlow confirm(long flowId, String authCode) {
        PortFlow flow = getFlow(flowId);
        if (flow.status() == PortStatus.EFFECTIVE) {
            throw BusinessException.conflict("CONFIRM_TOO_LATE", "流程已生效，确认不再被接受");
        }
        if (flow.status() != PortStatus.CODE_ISSUED) {
            throw BusinessException.conflict("INVALID_STATE",
                    "当前状态不允许携出方确认：" + flow.status());
        }
        if (!flow.authCode().equals(authCode)) {
            throw BusinessException.unprocessable("AUTH_CODE_MISMATCH", "授权码不匹配");
        }
        Instant now = clock.instant();
        if (!now.isBefore(flow.authCodeExpiresAt())) {
            throw BusinessException.unprocessable("AUTH_CODE_EXPIRED", "授权码已失效（恰满 24 小时即失效）");
        }
        if (portRepository.confirm(flowId, now) == 0) {
            throw BusinessException.conflict("STATE_RACE", "流程状态已变化，请刷新后重试");
        }
        portRepository.insertEvent(flowId, "CONFIRMED", "携出方已确认", now);
        return portRepository.findById(flowId).orElseThrow();
    }

    /**
     * 生效广播：仅在每日 02:00（含）至 04:00（不含，UTC）窗口内受理；
     * 窗口外拒绝并保留待生效状态。按 requestId 幂等，同一流程只产生一条生效记录。
     */
    @Transactional
    public EffectivenessRecord broadcast(long flowId, String requestId, String channel) {
        String fingerprint = fingerprint(requestId, String.valueOf(flowId), channel);
        var replay = portRepository.findEffectivenessByRequestId(requestId);
        if (replay.isPresent()) {
            EffectivenessRecord record = replay.get();
            if (!record.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已使用且业务字段不一致：" + requestId);
            }
            return record;
        }

        PortFlow flow = getFlow(flowId);
        if (flow.status() == PortStatus.EFFECTIVE) {
            // 同一流程重复广播（不同 requestId）：只返回既有生效记录
            return portRepository.findEffectivenessByFlowId(flowId).orElseThrow();
        }
        if (flow.status() != PortStatus.CONFIRMED) {
            throw BusinessException.conflict("INVALID_STATE",
                    "当前状态不允许生效广播：" + flow.status());
        }
        Instant now = clock.instant();
        if (!inWindow(now)) {
            throw BusinessException.unprocessable("OUTSIDE_WINDOW",
                    "当前不在生效窗口（每日 02:00-04:00 UTC，04:00 整点视为窗口外），流程保留待生效");
        }
        if (portRepository.markEffective(flowId, now) == 0) {
            throw BusinessException.conflict("STATE_RACE", "流程状态已变化（可能已被撤销），请刷新后重试");
        }
        var recordId = portRepository.insertEffectiveness(flowId, requestId, fingerprint,
                flow.msisdn(), now);
        portRepository.deleteInflight(flow.msisdn());
        portRepository.insertEvent(flowId, "EFFECTIVE", "生效窗口内广播生效，渠道：" + channel, now);
        return recordId
                .flatMap(id -> portRepository.findEffectivenessByFlowId(flowId))
                .orElseGet(() -> portRepository.findEffectivenessByFlowId(flowId).orElseThrow());
    }

    /**
     * 用户在生效前撤销；撤销为终态，与生效并发时形成唯一终态。
     */
    @Transactional
    public PortFlow cancel(long flowId, String reason) {
        PortFlow flow = getFlow(flowId);
        if (flow.status() == PortStatus.CANCELLED) {
            return flow;
        }
        if (flow.status() == PortStatus.EFFECTIVE) {
            throw BusinessException.conflict("ALREADY_EFFECTIVE", "流程已生效，不可撤销");
        }
        Instant now = clock.instant();
        if (portRepository.cancel(flowId, reason, now) == 0) {
            PortFlow current = portRepository.findById(flowId).orElseThrow();
            if (current.status() == PortStatus.EFFECTIVE) {
                throw BusinessException.conflict("ALREADY_EFFECTIVE", "流程已生效，不可撤销");
            }
            throw BusinessException.conflict("STATE_RACE", "流程状态已变化，请刷新后重试");
        }
        portRepository.deleteInflight(flow.msisdn());
        portRepository.insertEvent(flowId, "CANCELLED", "用户撤销：" + reason, now);
        return portRepository.findById(flowId).orElseThrow();
    }

    /**
     * 查询流程当前状态。
     */
    @Transactional(readOnly = true)
    public PortFlow getFlow(long flowId) {
        return portRepository.findById(flowId)
                .orElseThrow(() -> BusinessException.notFound(
                        "FLOW_NOT_FOUND", "携转流程不存在：" + flowId));
    }

    /**
     * 按发生顺序查询流程事件。
     */
    @Transactional(readOnly = true)
    public List<PortEvent> listEvents(long flowId) {
        getFlow(flowId);
        return portRepository.listEvents(flowId);
    }

    /**
     * 判断给定时刻是否处于生效窗口 [02:00, 04:00)（UTC）。
     */
    boolean inWindow(Instant instant) {
        LocalTime time = LocalTime.ofInstant(instant, ZoneOffset.UTC);
        return !time.isBefore(WINDOW_START) && time.isBefore(WINDOW_END);
    }

    private void checkEligibility(SubscriberInfo subscriber, String donorOperator, Instant now) {
        if (!subscriber.currentOperator().equals(donorOperator)) {
            throw BusinessException.unprocessable("DONOR_MISMATCH",
                    "携出方与当前运营商不一致：" + donorOperator);
        }
        if (subscriber.arrearsAmount().signum() > 0) {
            throw BusinessException.unprocessable("ELIGIBILITY_ARREARS", "存在欠费，不满足携转资格");
        }
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (subscriber.contractEndDate() != null && !subscriber.contractEndDate().isBefore(today)) {
            throw BusinessException.unprocessable("ELIGIBILITY_CONTRACT", "仍在网约定期限内，不满足携转资格");
        }
        if (subscriber.lastPortTime() != null
                && subscriber.lastPortTime().isAfter(now.minus(MIN_PORT_INTERVAL))) {
            throw BusinessException.unprocessable("ELIGIBILITY_INTERVAL",
                    "距上次携转未满 120 日，不满足携转资格");
        }
    }

    private PortFlow resolveReplay(PortFlow existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw BusinessException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已使用且业务字段不一致：" + existing.requestId());
        }
        return existing;
    }

    /**
     * 幂等指纹：requestId 与全部业务字段的 SHA-256。
     */
    static String fingerprint(String... fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                digest.update(field.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
