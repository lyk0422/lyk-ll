package com.example.starter.roaming.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.roaming.domain.BatchRow;
import com.example.starter.roaming.domain.DetailRow;
import com.example.starter.roaming.domain.DiscrepancyRow;
import com.example.starter.roaming.domain.IdempotentRow;
import com.example.starter.roaming.domain.SessionRow;
import com.example.starter.roaming.domain.SnapshotRow;
import com.example.starter.roaming.repo.RoamingRepository;
import com.example.starter.roaming.web.ApiException;
import com.example.starter.roaming.web.dto.AuthRequest;
import com.example.starter.roaming.web.dto.AuthResponse;
import com.example.starter.roaming.web.dto.BatchResponse;
import com.example.starter.roaming.web.dto.ClearingDetailDto;
import com.example.starter.roaming.web.dto.FreezeRequest;
import com.example.starter.roaming.web.dto.MeterRequest;
import com.example.starter.roaming.web.dto.MeterResponse;
import com.example.starter.roaming.web.dto.SealRequest;
import com.example.starter.roaming.web.dto.StopRequest;
import com.example.starter.roaming.web.dto.StopResponse;
import com.example.starter.roaming.web.dto.WriteOffRequest;
import com.example.starter.roaming.web.dto.WriteOffResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 充电漫游核心服务。
 *
 * <p>并发与幂等要点：</p>
 * <ul>
 *   <li>授权、启停、计量、冻结、封存、核销全部以 requestId 幂等：参数指纹覆盖
 *       requestId 与全部业务字段，同键同参回放首次结果，同键异参返回 409；</li>
 *   <li>同车同时刻唯一会话由车辆行锁 + (cert_fingerprint, auth_at) 唯一约束保证；</li>
 *   <li>快照按 (session_no, meter_seq) 去重，会话行锁串行化乱序归位；</li>
 *   <li>会话归批用 batch_id IS NULL 条件更新，结束/封存并发下归属唯一批次；</li>
 *   <li>封存批行锁与迟到计量互斥，封存后迟到快照转入差异挂账；</li>
 *   <li>挂账以 OPEN -&gt; WRITTEN_OFF 条件更新核销，唯一一次。</li>
 * </ul>
 */
@Service
public class RoamingService {

    /** 授权有效期：5 分钟。 */
    static final Duration AUTH_TTL = Duration.ofMinutes(5);

    private final RoamingRepository repo;
    private final java.time.Clock clock;
    private final ObjectMapper objectMapper;

    public RoamingService(RoamingRepository repo, java.time.Clock clock, ObjectMapper objectMapper) {
        this.repo = repo;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ================= 授权（暨会话启动） =================

    @Transactional
    public ServiceResult authorize(AuthRequest request) {
        String fingerprint = fingerprint("AUTH",
                request.requestId(), request.certFingerprint(), request.pointId(),
                normalized(request.requestTime()));
        return runIdempotent(request.requestId(), "AUTH", fingerprint, () -> {
            LocalDateTime now = nowUtc();
            LocalDateTime authAt = request.requestTime().withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();

            String homeOperator = repo.findHomeOperator(request.certFingerprint());
            if (homeOperator == null) {
                throw new ApiException(404, "UNKNOWN_VEHICLE",
                        "车辆证书指纹不存在: " + request.certFingerprint());
            }
            String chargingOperator = repo.findPointOperator(request.pointId());
            if (chargingOperator == null) {
                throw new ApiException(404, "UNKNOWN_POINT", "桩编号不存在: " + request.pointId());
            }

            // 锁车辆行：同车授权事务串行化，ACTIVE 检查无并发遗漏。
            repo.lockVehicle(request.certFingerprint());

            SessionRow active = repo.findActiveSessionForVehicle(request.certFingerprint());
            if (active != null) {
                if (isWithinAuthWindow(active, now)) {
                    // 授权有效期内重复请求：返回首次授权结果。
                    return ServiceResult.ok(authResponse(request.requestId(), active, true));
                }
                throw new ApiException(409, "SESSION_IN_PROGRESS",
                        "车辆已有进行中的充电会话，且首次授权已超过 5 分钟有效期: "
                                + active.sessionNo());
            }

            String sessionNo = newSessionNo();
            SessionRow row = new SessionRow(null, sessionNo, request.certFingerprint(),
                    request.pointId(), homeOperator, chargingOperator, authAt, authAt, null,
                    SessionRow.STATUS_ACTIVE, 0, 0, null, null, now, now);
            try {
                repo.insertSession(row);
            } catch (DuplicateKeyException ex) {
                // 同车同刻并发授权：唯一会话成功者回放。
                SessionRow winner = repo.findSessionByFingerprintAndAuthTime(
                        request.certFingerprint(), authAt);
                if (winner != null && isWithinAuthWindow(winner, now)) {
                    return ServiceResult.ok(authResponse(request.requestId(), winner, true));
                }
                throw new ApiException(409, "SESSION_CONFLICT",
                        "同一车辆同一时刻只允许一个充电会话");
            }
            SessionRow created = repo.findSessionByNo(sessionNo);
            return ServiceResult.ok(authResponse(request.requestId(), created, false));
        });
    }

    // ================= 会话结束 =================

    @Transactional
    public ServiceResult stopSession(StopRequest request) {
        requireText(request.requestId(), "requestId");
        requireText(request.sessionNo(), "sessionNo");
        String endNormalized = request.endTime() == null ? "" : normalized(request.endTime());
        String fingerprint = fingerprint("STOP", request.requestId(), request.sessionNo(),
                endNormalized);
        return runIdempotent(request.requestId(), "STOP", fingerprint, () -> {
            LocalDateTime now = nowUtc();

            // 行锁会话：与冻结归批并发时，二者对同一行的加锁顺序决定唯一归属。
            repo.lockSession(request.sessionNo());
            SessionRow session = repo.findSessionByNo(request.sessionNo());
            if (session == null) {
                throw new ApiException(404, "SESSION_NOT_FOUND",
                        "会话不存在: " + request.sessionNo());
            }
            LocalDateTime endAt = request.endTime() == null
                    ? now
                    : request.endTime().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            if (endAt.isBefore(session.startAt())) {
                throw new ApiException(400, "INVALID_END_TIME",
                        "结束时刻不得早于会话启动时刻");
            }

            if (SessionRow.STATUS_ENDED.equals(session.status())) {
                // 会话已结束：回放既有结束结果，不重复结束、不改动电量。
                return ServiceResult.ok(stopResponse(request.requestId(), session, true));
            }

            int updated = repo.casEndSession(request.sessionNo(), endAt, now);
            if (updated == 0) {
                SessionRow current = repo.findSessionByNo(request.sessionNo());
                return ServiceResult.ok(stopResponse(request.requestId(), current, true));
            }
            repo.recomputeSessionEnergy(request.sessionNo(), now);
            SessionRow ended = repo.findSessionByNo(request.sessionNo());
            return ServiceResult.ok(stopResponse(request.requestId(), ended, false));
        });
    }

    // ================= 计量快照 =================

    @Transactional
    public ServiceResult uploadMeter(MeterRequest request) {
        requireText(request.requestId(), "requestId");
        requireText(request.sessionNo(), "sessionNo");
        if (request.meterSeq() == null || request.meterSeq() <= 0) {
            throw new ApiException(400, "INVALID_METER_SEQ", "meterSeq 必须为正整数");
        }
        if (request.meterWh() == null || request.meterWh() < 0) {
            throw new ApiException(400, "INVALID_METER_WH", "meterWh 不能为负");
        }
        if (request.snapshotAt() == null) {
            throw new ApiException(400, "INVALID_SNAPSHOT_TIME", "snapshotAt 不能为空");
        }
        String fingerprint = fingerprint("METER", request.requestId(), request.sessionNo(),
                String.valueOf(request.meterSeq()), String.valueOf(request.meterWh()),
                normalized(request.snapshotAt()));
        return runIdempotent(request.requestId(), "METER", fingerprint, () -> {
            LocalDateTime now = nowUtc();
            LocalDateTime snapshotAt = request.snapshotAt()
                    .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();

            // 先锁会话行：同一会话的乱序/并发快照串行归位。
            repo.lockSession(request.sessionNo());
            SessionRow session = repo.findSessionByNo(request.sessionNo());
            if (session == null) {
                throw new ApiException(404, "SESSION_NOT_FOUND",
                        "会话不存在: " + request.sessionNo());
            }

            SnapshotRow existing = repo.findSnapshot(request.sessionNo(), request.meterSeq());
            boolean duplicate = existing != null;
            if (!duplicate) {
                try {
                    repo.insertSnapshot(request.sessionNo(), request.meterSeq(),
                            request.meterWh(), snapshotAt, request.requestId(), now);
                } catch (DuplicateKeyException ex) {
                    // 同序号并发上传：按已有快照归位，不重计。
                    duplicate = true;
                }
                repo.recomputeSessionEnergy(request.sessionNo(), now);
                session = repo.findSessionByNo(request.sessionNo());
            }

            // 已归批会话：锁批次，判断封存状态；与封存事务互斥。
            BatchRow batch = null;
            DiscrepancyRow discrepancy = null;
            if (session.batchId() != null) {
                batch = repo.lockBatch(session.batchId());
            }
            if (batch != null && BatchRow.STATUS_SEALED.equals(batch.status())) {
                // 封存后迟到的计量快照：进入差异挂账（同会话同序号只挂一次）。
                discrepancy = repo.findDiscrepancyBySessionSeq(request.sessionNo(),
                        request.meterSeq());
                if (discrepancy == null) {
                    String discrepancyNo = newDiscrepancyNo();
                    DiscrepancyRow draft = new DiscrepancyRow(null, discrepancyNo,
                            request.sessionNo(), request.meterSeq(),
                            duplicate ? existing.meterWh() : request.meterWh(),
                            duplicate ? existing.snapshotAt() : snapshotAt,
                            request.requestId(), batch.id(),
                            DiscrepancyRow.STATUS_OPEN, null, null, null, null, now);
                    try {
                        repo.insertDiscrepancy(draft, now);
                        discrepancy = repo.findDiscrepancyByNo(discrepancyNo);
                    } catch (DuplicateKeyException ex) {
                        discrepancy = repo.findDiscrepancyBySessionSeq(request.sessionNo(),
                                request.meterSeq());
                    }
                }
            }

            return ServiceResult.ok(new MeterResponse(
                    request.requestId(),
                    true,
                    duplicate,
                    request.sessionNo(),
                    request.meterSeq(),
                    duplicate ? existing.meterWh() : request.meterWh(),
                    (duplicate ? existing.snapshotAt() : snapshotAt)
                            .atOffset(ZoneOffset.UTC).toInstant(),
                    session.lastMeterSeq(),
                    session.totalEnergyWh(),
                    batch == null ? null : batch.batchNo(),
                    batch == null ? null : batch.status(),
                    discrepancy == null ? null : discrepancy.discrepancyNo()));
        });
    }

    // ================= 清分批冻结与计算 =================

    @Transactional
    public ServiceResult freezeBatch(FreezeRequest request) {
        requireText(request.requestId(), "requestId");
        LocalDate day = request.settlementDay() != null
                ? request.settlementDay()
                : nowUtc().toLocalDate();
        String fingerprint = fingerprint("FREEZE", request.requestId(), day.toString());
        return runIdempotent(request.requestId(), "FREEZE", fingerprint, () -> {
            LocalDateTime now = nowUtc();
            String batchNo = newBatchNo();
            long batchId = repo.insertBatch(batchNo, day, now);

            // 条件归批：仅当日已结束且未归批的跨运营商会话。
            // 与结束会话并发时，行锁 + batch_id IS NULL 保证会话归属唯一批次。
            List<SessionRow> sessions = repo.assignEndedSessionsToBatch(batchId, day);

            BigDecimal receivableSum = BigDecimal.ZERO;
            BigDecimal payableSum = BigDecimal.ZERO;
            long energySum = 0;
            for (SessionRow s : sessions) {
                BigDecimal homeRate = requireRate(s.homeOperator());
                BigDecimal chargingRate = requireRate(s.chargingOperator());
                BigDecimal receivable = money(s.totalEnergyWh(), homeRate);
                BigDecimal payable = money(s.totalEnergyWh(), chargingRate);
                repo.insertDetail(batchId, s, receivable, payable, now);
                receivableSum = receivableSum.add(receivable);
                payableSum = payableSum.add(payable);
                energySum += s.totalEnergyWh();
            }
            repo.updateBatchTotals(batchId, sessions.size(), energySum, receivableSum, payableSum);

            BatchRow batch = repo.findBatchById(batchId);
            List<DetailRow> details = repo.findDetailsByBatch(batchId);
            return ServiceResult.ok(toBatchResponse(request.requestId(), batch, details));
        });
    }

    // ================= 清分批封存 =================

    @Transactional
    public ServiceResult sealBatch(SealRequest request) {
        requireText(request.requestId(), "requestId");
        requireText(request.batchNo(), "batchNo");
        String fingerprint = fingerprint("SEAL", request.requestId(), request.batchNo());
        return runIdempotent(request.requestId(), "SEAL", fingerprint, () -> {
            LocalDateTime now = nowUtc();
            BatchRow batch = repo.findBatchByNo(request.batchNo());
            if (batch == null) {
                throw new ApiException(404, "BATCH_NOT_FOUND", "清分批不存在: " + request.batchNo());
            }

            // 锁批：与迟到计量互斥。已封存则直接返回首次清分单，不重新计算。
            BatchRow locked = repo.lockBatch(batch.id());
            if (BatchRow.STATUS_SEALED.equals(locked.status())) {
                List<DetailRow> details = repo.findDetailsByBatch(batch.id());
                return ServiceResult.ok(toBatchResponse(request.requestId(), locked, details));
            }

            // 封存前按各会话最新累计电量重算明细，再以明细之和回写批合计，保证合计一致。
            List<SessionRow> sessions = repo.findSessionsByBatch(batch.id());
            BigDecimal receivableSum = BigDecimal.ZERO;
            BigDecimal payableSum = BigDecimal.ZERO;
            long energySum = 0;
            for (SessionRow s : sessions) {
                BigDecimal receivable = money(s.totalEnergyWh(), requireRate(s.homeOperator()));
                BigDecimal payable = money(s.totalEnergyWh(), requireRate(s.chargingOperator()));
                repo.updateDetailAmounts(batch.id(), s.sessionNo(), s.totalEnergyWh(),
                        receivable, payable);
                receivableSum = receivableSum.add(receivable);
                payableSum = payableSum.add(payable);
                energySum += s.totalEnergyWh();
            }
            repo.updateBatchTotals(batch.id(), sessions.size(), energySum,
                    receivableSum, payableSum);
            int sealed = repo.casSealBatch(batch.id(), now);
            if (sealed == 0) {
                throw new ApiException(409, "BATCH_STATE_CHANGED",
                        "清分批状态已变化，请重试: " + request.batchNo());
            }
            BatchRow sealedBatch = repo.findBatchById(batch.id());
            List<DetailRow> details = repo.findDetailsByBatch(batch.id());
            return ServiceResult.ok(toBatchResponse(request.requestId(), sealedBatch, details));
        });
    }

    // ================= 差异挂账核销 =================

    @Transactional
    public ServiceResult writeOff(WriteOffRequest request) {
        requireText(request.requestId(), "requestId");
        requireText(request.discrepancyNo(), "discrepancyNo");
        requireText(request.operator(), "operator");
        requireText(request.remark(), "remark");
        String fingerprint = fingerprint("WRITE_OFF", request.requestId(),
                request.discrepancyNo(), request.operator(), request.remark());
        return runIdempotent(request.requestId(), "WRITE_OFF", fingerprint, () -> {
            LocalDateTime now = nowUtc();
            DiscrepancyRow discrepancy = repo.lockDiscrepancyByNo(request.discrepancyNo());
            if (discrepancy == null) {
                throw new ApiException(404, "DISCREPANCY_NOT_FOUND",
                        "差异挂账不存在: " + request.discrepancyNo());
            }
            if (DiscrepancyRow.STATUS_WRITTEN_OFF.equals(discrepancy.status())) {
                // 同一挂账只允许核销一次：再次核销（新 requestId）拒绝并返回既有留痕。
                throw new ApiException(409, "DISCREPANCY_ALREADY_WRITTEN_OFF",
                        "挂账已核销，核销人: " + discrepancy.writeOffBy());
            }
            int updated = repo.casWriteOff(discrepancy.id(), now, request.operator(),
                    request.remark(), request.requestId());
            if (updated == 0) {
                DiscrepancyRow current = repo.findDiscrepancyByNo(request.discrepancyNo());
                throw new ApiException(409, "DISCREPANCY_ALREADY_WRITTEN_OFF",
                        "挂账已核销，核销人: " + current.writeOffBy());
            }
            DiscrepancyRow done = repo.findDiscrepancyByNo(request.discrepancyNo());
            return ServiceResult.ok(toWriteOffResponse(request.requestId(), done));
        });
    }

    // ================= 幂等模板 =================

    /**
     * requestId 幂等执行模板：
     * <ul>
     *   <li>同键同参：回放首次响应（含首次的 HTTP 状态，业务错误同样回放）；</li>
     *   <li>同键异参：409，不执行业务、不产生新会话或新计量；</li>
     *   <li>新键：执行业务并固化首次结果。业务异常在同事务内固化后原样返回。</li>
     * </ul>
     */
    private ServiceResult runIdempotent(String requestId, String kind, String paramFingerprint,
                                        Supplier<Object> action) {
        IdempotentRow existing;
        try {
            repo.insertIdempotentClaim(requestId, kind, paramFingerprint, nowUtc());
            existing = null;
        } catch (DuplicateKeyException ex) {
            existing = repo.findIdempotent(requestId);
        }
        if (existing != null) {
            if (!existing.paramFingerprint().equals(paramFingerprint)) {
                // 同键异参：409 返回，不执行业务、不产生新会话或新计量。
                return new ServiceResult(409, new ErrorBodyView("IDEMPOTENT_PARAM_CONFLICT",
                        "requestId 已用于不同参数的请求: " + requestId));
            }
            return new ServiceResult(existing.httpStatus(), readJson(existing.responseBody()));
        }

        try {
            Object body = action.get();
            ServiceResult result = (ServiceResult) body;
            repo.completeIdempotent(requestId, result.status(), writeJson(result.body()));
            return result;
        } catch (ApiException ex) {
            ErrorBodyView error = new ErrorBodyView(ex.getErrorCode(), ex.getMessage());
            repo.completeIdempotent(requestId, ex.getStatus(), writeJson(error));
            return new ServiceResult(ex.getStatus(), error);
        }
    }

    // ================= 辅助方法 =================

    private boolean isWithinAuthWindow(SessionRow session, LocalDateTime now) {
        return !now.isAfter(session.authAt().plus(AUTH_TTL));
    }

    private AuthResponse authResponse(String requestId, SessionRow s, boolean cached) {
        Instant authAt = s.authAt().atOffset(ZoneOffset.UTC).toInstant();
        return new AuthResponse(
                requestId,
                true,
                cached,
                s.sessionNo(),
                s.certFingerprint(),
                s.pointId(),
                s.homeOperator(),
                s.chargingOperator(),
                authAt,
                authAt.plus(AUTH_TTL),
                s.status());
    }

    private StopResponse stopResponse(String requestId, SessionRow s, boolean cached) {
        return new StopResponse(
                requestId,
                cached,
                s.sessionNo(),
                s.status(),
                s.startAt().atOffset(ZoneOffset.UTC).toInstant(),
                s.endAt() == null ? null : s.endAt().atOffset(ZoneOffset.UTC).toInstant(),
                s.totalEnergyWh(),
                s.lastMeterSeq());
    }

    private BatchResponse toBatchResponse(String requestId, BatchRow batch,
                                          List<DetailRow> details) {
        List<ClearingDetailDto> dtos = details.stream()
                .map(d -> new ClearingDetailDto(d.sessionNo(), d.homeOperator(),
                        d.chargingOperator(), d.energyWh(), d.receivableAmount(),
                        d.payableAmount()))
                .toList();
        return new BatchResponse(
                requestId,
                batch.batchNo(),
                batch.settlementDay(),
                batch.status(),
                batch.sessionCount(),
                batch.totalEnergyWh(),
                batch.totalReceivable(),
                batch.totalPayable(),
                batch.frozenAt().atOffset(ZoneOffset.UTC).toInstant(),
                batch.sealedAt() == null ? null
                        : batch.sealedAt().atOffset(ZoneOffset.UTC).toInstant(),
                dtos);
    }

    private WriteOffResponse toWriteOffResponse(String requestId, DiscrepancyRow d) {
        return new WriteOffResponse(
                requestId,
                d.discrepancyNo(),
                d.sessionNo(),
                d.meterSeq(),
                d.meterWh(),
                d.status(),
                d.writeOffAt().atOffset(ZoneOffset.UTC).toInstant(),
                d.writeOffBy(),
                d.writeOffRemark());
    }

    /** 电量 Wh 按费率（元/kWh）计价，四舍五入保留 4 位小数。 */
    private BigDecimal money(long energyWh, BigDecimal ratePerKwh) {
        return BigDecimal.valueOf(energyWh)
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                .multiply(ratePerKwh)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal requireRate(String operatorCode) {
        BigDecimal rate = repo.findOperatorRate(operatorCode);
        if (rate == null) {
            throw new ApiException(500, "OPERATOR_RATE_MISSING",
                    "运营商费率缺失: " + operatorCode);
        }
        return rate;
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, "INVALID_REQUEST", field + " 不能为空");
        }
    }

    private String normalized(OffsetDateTime time) {
        return time.withOffsetSameInstant(ZoneOffset.UTC).toInstant().toString();
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    private String newSessionNo() {
        return "CS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private String newBatchNo() {
        return "CB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private String newDiscrepancyNo() {
        return "DC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    /** 业务字段参数指纹：SHA-256，覆盖 requestId 与全部业务字段。 */
    private String fingerprint(String kind, String... parts) {
        String joined = kind + "|" + String.join("|", parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private Object readJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }

    /** 固化到幂等表的错误响应视图，字段与 {@code ErrorResponse} 一致。 */
    private record ErrorBodyView(String errorCode, String message) {
    }
}
