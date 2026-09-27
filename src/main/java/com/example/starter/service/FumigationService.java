package com.example.starter.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.CreateFumigationRequest;
import com.example.starter.dto.FumigationDtos.FinishFumigationRequest;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.GasReadingRequest;
import com.example.starter.dto.FumigationDtos.GasReadingResponse;
import com.example.starter.dto.FumigationDtos.GasReadingView;
import com.example.starter.dto.FumigationDtos.ReleaseConfirmRequest;
import com.example.starter.dto.FumigationDtos.StartFumigationRequest;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;
import com.example.starter.util.Fingerprints;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 熏蒸作业服务：申请（UTC 半开作业窗与通风窗互斥）、双审批、开始（原子锁仓并冻结方案版本）、
 * 结束转散气（绑定空闲风机）、合成气体读数（序号唯一、时刻严格递增）、双复核解除
 * （重查最新读数与版本，原子释放仓锁与风机）。仅合成规则，不计算真实药剂剂量。
 *
 * <p>写操作均以 requestId 幂等：同键同参重放首次冻结结果，异参 409，失败不占键；
 * 修改操作携带 expectedVersion，版本从 1 开始、成功变更加 1，不一致返回 409。
 * 业务拒绝返回 422，参数非法 400，对象不存在 404。</p>
 */
@Service
public class FumigationService {

    /** 解除所需的连续安全点数 */
    static final int SAFE_POINTS_REQUIRED = 3;

    /** 连续安全点首末最小间隔 */
    static final Duration MIN_SAFE_SPAN = Duration.ofMinutes(30);

    /** 通风作业窗的存储时区（与既有通风服务一致） */
    private static final ZoneId VENTILATION_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final SiloService siloService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FumigationService(JdbcTemplate jdbc, SiloService siloService,
                             ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.siloService = siloService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 申请熏蒸：作业窗为 UTC 半开区间，与同仓普通通风窗及既有熏蒸窗互斥（端点相接可共存）。
     */
    @Transactional
    public FumigationView create(long siloId, CreateFumigationRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), siloId, request.windowStart(),
                request.windowEnd(), request.safetyThreshold(), request.planSteps());
        return mutate(request.requestId(), "CREATE", null, fingerprint, () -> {
            if (!request.windowStart().isBefore(request.windowEnd())) {
                throw ApiException.badRequest("INVALID_WINDOW", "作业窗起点必须早于终点");
            }
            if (request.safetyThreshold().signum() < 0) {
                throw ApiException.badRequest("INVALID_THRESHOLD", "合成安全阈值须为非负小数");
            }
            SiloRow silo = lockSilo(siloId);
            if (SiloStatus.ARCHIVED.name().equals(silo.status())) {
                throw ApiException.unprocessable("SILO_ARCHIVED", "粮仓已归档，禁止申请熏蒸");
            }
            ensureNoVentilationWindowConflict(siloId, request.windowStart(), request.windowEnd());
            ensureNoFumigationWindowConflict(siloId, null, request.windowStart(), request.windowEnd());

            long id = siloService.insert(
                    "INSERT INTO fumigation (silo_id, window_start, window_end, safety_threshold, plan_steps)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    siloId, toUtc(request.windowStart()), toUtc(request.windowEnd()),
                    request.safetyThreshold(), toJson(request.planSteps()));
            return toView(findById(id).orElseThrow(), false);
        });
    }

    /**
     * 审批：须两名不同审批人；每次成功审批版本加 1，集齐两人后转为已批准。
     */
    @Transactional
    public FumigationView approve(long fumigationId, ApproveRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), fumigationId,
                request.expectedVersion(), request.approver());
        return mutate(request.requestId(), "APPROVE", fumigationId, fingerprint, () -> {
            FumigationRow row = lockById(fumigationId);
            checkVersion(row, request.expectedVersion());
            if (row.status() != FumigationStatus.PENDING_APPROVAL) {
                throw ApiException.unprocessable("INVALID_STATE",
                        "当前状态不允许审批: " + row.status());
            }
            Integer duplicate = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fumigation_approval WHERE fumigation_id = ? AND approver = ?",
                    Integer.class, fumigationId, request.approver());
            if (duplicate != null && duplicate > 0) {
                throw ApiException.unprocessable("DUPLICATE_APPROVER",
                        "审批人已审批过，须两名不同审批人: " + request.approver());
            }
            jdbc.update("INSERT INTO fumigation_approval (fumigation_id, approver) VALUES (?, ?)",
                    fumigationId, request.approver());
            int approvals = countApprovals(fumigationId);
            String nextStatus = approvals >= 2
                    ? FumigationStatus.APPROVED.name() : FumigationStatus.PENDING_APPROVAL.name();
            jdbc.update("UPDATE fumigation SET status = ?, version = version + 1 WHERE id = ?",
                    nextStatus, fumigationId);
            return toView(findById(fumigationId).orElseThrow(), false);
        });
    }

    /**
     * 开始熏蒸：实际开始时再查无执行中通风；原子锁仓并冻结方案版本，
     * 此后禁止人员进入与普通通风（粮仓状态置为 FUMIGATING）。
     */
    @Transactional
    public FumigationView start(long fumigationId, StartFumigationRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), fumigationId, request.expectedVersion());
        return mutate(request.requestId(), "START", fumigationId, fingerprint, () -> {
            FumigationRow row = lockById(fumigationId);
            checkVersion(row, request.expectedVersion());
            if (row.status() != FumigationStatus.APPROVED) {
                throw ApiException.unprocessable("INVALID_STATE",
                        "仅已批准熏蒸单可开始，当前: " + row.status());
            }
            SiloRow silo = lockSilo(row.siloId());
            if (SiloStatus.ARCHIVED.name().equals(silo.status())) {
                throw ApiException.unprocessable("SILO_ARCHIVED", "粮仓已归档，禁止开始熏蒸");
            }
            if (SiloStatus.FUMIGATING.name().equals(silo.status())) {
                throw ApiException.unprocessable("SILO_LOCKED", "粮仓已有熏蒸/散气作业进行中");
            }
            Integer executing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ventilation_job WHERE silo_id = ? AND status = 'EXECUTING'",
                    Integer.class, row.siloId());
            if (executing != null && executing > 0) {
                throw ApiException.unprocessable("VENTILATION_EXECUTING",
                        "存在执行中通风作业，禁止开始熏蒸");
            }
            jdbc.update("UPDATE silo SET status = 'FUMIGATING' WHERE id = ?", row.siloId());
            jdbc.update("UPDATE fumigation SET status = 'FUMIGATING', frozen_version = version,"
                            + " prev_silo_status = ?, started_at = ?, version = version + 1 WHERE id = ?",
                    silo.status(), toUtc(Instant.now(clock)), fumigationId);
            return toView(findById(fumigationId).orElseThrow(), false);
        });
    }

    /**
     * 结束熏蒸转散气：原子绑定一台空闲风机（沿用既有唯一资源规则），失败则整体回滚。
     */
    @Transactional
    public FumigationView finish(long fumigationId, FinishFumigationRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), fumigationId,
                request.expectedVersion(), request.fanId());
        return mutate(request.requestId(), "FINISH", fumigationId, fingerprint, () -> {
            FumigationRow row = lockById(fumigationId);
            checkVersion(row, request.expectedVersion());
            if (row.status() != FumigationStatus.FUMIGATING) {
                throw ApiException.unprocessable("INVALID_STATE",
                        "仅熏蒸中熏蒸单可结束转散气，当前: " + row.status());
            }
            siloService.getFan(request.fanId());
            int locked = jdbc.update(
                    "UPDATE fan SET status = 'LOCKED', locked_job_id = ? WHERE id = ? AND status = 'IDLE'",
                    fumigationId, request.fanId());
            if (locked == 0) {
                throw ApiException.unprocessable("FAN_BUSY", "风机已被占用: " + request.fanId());
            }
            jdbc.update("UPDATE fumigation SET status = 'AERATING', fan_id = ?,"
                            + " aeration_started_at = ?, version = version + 1 WHERE id = ?",
                    request.fanId(), toUtc(Instant.now(clock)), fumigationId);
            return toView(findById(fumigationId).orElseThrow(), false);
        });
    }

    /**
     * 散气采样：读数以（单号, 序号）唯一，时刻严格递增，浓度为有限非负小数；
     * 解除后读数只记迟到事件，不改已冻结证据；requestId 幂等。
     */
    @Transactional
    public GasReadingResponse sample(long fumigationId, GasReadingRequest request) {
        if (request.seq() == null || request.seq() < 1) {
            throw ApiException.badRequest("INVALID_SEQ", "读数序号须为不小于1的整数");
        }
        if (request.concentration() == null || request.concentration().signum() < 0) {
            throw ApiException.badRequest("INVALID_CONCENTRATION", "浓度须为有限非负小数");
        }
        if (request.measuredAt() == null) {
            throw ApiException.badRequest("INVALID_MEASURED_AT", "采样时刻不能为空");
        }
        String fingerprint = Fingerprints.of(request.requestId(), fumigationId, request.seq(),
                request.concentration(), request.measuredAt());

        Optional<GasRow> existing = findGasByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayGas(existing.get(), fingerprint);
        }
        Optional<Long> late = findLateEvent(request.requestId());
        if (late.isPresent()) {
            return new GasReadingResponse("LATE_RECORDED", null, request.seq(), true);
        }

        FumigationRow row = lockById(fumigationId);

        // 持锁后复检幂等键，覆盖并发下同键请求在锁前检查落空的情形
        Optional<GasRow> rechecked = findGasByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            return replayGas(rechecked.get(), fingerprint);
        }

        if (row.status() == FumigationStatus.RELEASED) {
            // 关闭后读数只记迟到，不改已冻结证据
            try {
                recordLateGasReading(row, request);
            } catch (DuplicateKeyException duplicate) {
                return new GasReadingResponse("LATE_RECORDED", null, request.seq(), true);
            }
            return new GasReadingResponse("LATE_RECORDED", null, request.seq(), false);
        }
        if (row.status() != FumigationStatus.AERATING) {
            throw ApiException.unprocessable("INVALID_STATE",
                    "仅散气中可采样，当前: " + row.status());
        }
        Integer sameSeq = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gas_reading WHERE fumigation_id = ? AND seq = ?",
                Integer.class, fumigationId, request.seq());
        if (sameSeq != null && sameSeq > 0) {
            throw ApiException.unprocessable("DUPLICATE_SEQ", "读数序号已存在: " + request.seq());
        }
        java.sql.Timestamp maxMeasuredAt = jdbc.queryForObject(
                "SELECT MAX(measured_at) FROM gas_reading WHERE fumigation_id = ?",
                java.sql.Timestamp.class, fumigationId);
        if (maxMeasuredAt != null
                && !toUtc(request.measuredAt()).isAfter(maxMeasuredAt.toLocalDateTime())) {
            throw ApiException.unprocessable("NON_INCREASING_TIME",
                    "采样时刻须严格递增，须晚于: " + maxMeasuredAt.toLocalDateTime());
        }
        try {
            long id = siloService.insert(
                    "INSERT INTO gas_reading (fumigation_id, request_id, seq, concentration, measured_at,"
                            + " fingerprint) VALUES (?, ?, ?, ?, ?, ?)",
                    fumigationId, request.requestId(), request.seq(), request.concentration(),
                    toUtc(request.measuredAt()), fingerprint);
            return new GasReadingResponse("RECORDED", id, request.seq(), false);
        } catch (DuplicateKeyException duplicate) {
            // 跨单并发下同键请求落入唯一约束：按幂等重放或键冲突处理
            GasRow gas = findGasByRequestId(request.requestId()).orElseThrow(() -> duplicate);
            return replayGas(gas, fingerprint);
        }
    }

    /**
     * 解除复核：须两名不同复核人；最终确认时在行锁内重查最新读数与版本，
     * 满足连续三点安全规则后原子结束散气、释放仓锁与风机，任一失败整体回滚均不释放。
     */
    @Transactional
    public FumigationView confirmRelease(long fumigationId, ReleaseConfirmRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), fumigationId,
                request.expectedVersion(), request.reviewer());
        return mutate(request.requestId(), "RELEASE_CONFIRM", fumigationId, fingerprint, () -> {
            FumigationRow row = lockById(fumigationId);
            checkVersion(row, request.expectedVersion());
            if (row.status() != FumigationStatus.AERATING) {
                throw ApiException.unprocessable("INVALID_STATE",
                        "仅散气中可确认解除，当前: " + row.status());
            }
            if (row.releaseReviewer1() == null) {
                jdbc.update("UPDATE fumigation SET release_reviewer1 = ?, version = version + 1"
                        + " WHERE id = ?", request.reviewer(), fumigationId);
                return toView(findById(fumigationId).orElseThrow(), false);
            }
            if (row.releaseReviewer1().equals(request.reviewer())) {
                throw ApiException.unprocessable("SAME_REVIEWER",
                        "须两名不同复核人，复核人已确认过: " + request.reviewer());
            }
            // 最终确认：行锁内重查最新读数，高浓度补录与解除竞争不得用旧三点放行
            if (!releaseEligible(fumigationId, row.safetyThreshold())) {
                throw ApiException.unprocessable("RELEASE_NOT_SAFE",
                        "未满足连续三点≤安全阈值且首末间隔≥30分钟，禁止解除");
            }
            jdbc.update("UPDATE fumigation SET status = 'RELEASED', released_at = ?,"
                            + " version = version + 1 WHERE id = ?",
                    toUtc(Instant.now(clock)), fumigationId);
            int fanReleased = jdbc.update(
                    "UPDATE fan SET status = 'IDLE', locked_job_id = NULL"
                            + " WHERE locked_job_id = ? AND status = 'LOCKED'",
                    fumigationId);
            if (fanReleased != 1) {
                throw new IllegalStateException("释放风机失败，整体回滚: " + fumigationId);
            }
            int siloReleased = jdbc.update(
                    "UPDATE silo SET status = ? WHERE id = ? AND status = 'FUMIGATING'",
                    row.prevSiloStatus(), row.siloId());
            if (siloReleased != 1) {
                throw new IllegalStateException("释放仓锁失败，整体回滚: " + fumigationId);
            }
            return toView(findById(fumigationId).orElseThrow(), false);
        });
    }

    /** 按 ID 查询熏蒸单。 */
    public FumigationView getFumigation(long fumigationId) {
        return toView(findById(fumigationId).orElseThrow(
                () -> ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + fumigationId)), false);
    }

    /** 粮仓全部熏蒸单（按申请先后）。 */
    public List<FumigationView> listBySilo(long siloId) {
        siloService.getSilo(siloId);
        return jdbc.query("SELECT * FROM fumigation WHERE silo_id = ? ORDER BY id",
                FumigationService::mapRow, siloId).stream().map(row -> toView(row, false)).toList();
    }

    /** 熏蒸单全部气体读数（按序号，可复算连续安全点）。 */
    public List<GasReadingView> gasReadings(long fumigationId) {
        findById(fumigationId).orElseThrow(
                () -> ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + fumigationId));
        return jdbc.query("SELECT * FROM gas_reading WHERE fumigation_id = ? ORDER BY seq",
                (rs, rowNum) -> new GasReadingView(rs.getLong("id"), rs.getLong("fumigation_id"),
                        rs.getInt("seq"), rs.getBigDecimal("concentration"),
                        toInstant(rs.getTimestamp("measured_at").toLocalDateTime())),
                fumigationId);
    }

    /**
     * 解除资格：当前连续安全段（≤阈值且序号连续，越限与缺号均打断）内，
     * 存在连续三点首末间隔≥30分钟（恰等合法）。
     */
    private boolean releaseEligible(long fumigationId, BigDecimal threshold) {
        List<GasPoint> points = jdbc.query(
                "SELECT seq, concentration, measured_at FROM gas_reading"
                        + " WHERE fumigation_id = ? ORDER BY seq",
                (rs, rowNum) -> new GasPoint(rs.getInt("seq"), rs.getBigDecimal("concentration"),
                        rs.getTimestamp("measured_at").toLocalDateTime()),
                fumigationId);
        int streakStart = points.size();
        for (int i = points.size() - 1; i >= 0; i--) {
            GasPoint point = points.get(i);
            if (point.concentration().compareTo(threshold) > 0) {
                break;
            }
            if (i < points.size() - 1 && points.get(i + 1).seq() != point.seq() + 1) {
                break;
            }
            streakStart = i;
        }
        int streakLength = points.size() - streakStart;
        if (streakLength < SAFE_POINTS_REQUIRED) {
            return false;
        }
        for (int i = streakStart; i + SAFE_POINTS_REQUIRED - 1 < points.size(); i++) {
            Duration span = Duration.between(points.get(i).measuredAt(),
                    points.get(i + SAFE_POINTS_REQUIRED - 1).measuredAt());
            if (span.compareTo(MIN_SAFE_SPAN) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 幂等写操作骨架：同键同参重放首次冻结结果，异参 409；先占键后执行，
     * 业务失败整体回滚不占键；业务、版本与幂等结果同事务。
     */
    private FumigationView mutate(String requestId, String action, Long fumigationId,
                                  String fingerprint, Supplier<FumigationView> business) {
        Optional<RequestRow> existing = findRequest(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint, requestId);
        }
        try {
            jdbc.update("INSERT INTO fumigation_request (request_id, action, fumigation_id, fingerprint,"
                    + " response_body) VALUES (?, ?, ?, ?, '')", requestId, action, fumigationId, fingerprint);
        } catch (DuplicateKeyException duplicate) {
            // 并发下同键请求落入唯一约束：按幂等重放或键冲突处理（本事务尚无业务变更）
            RequestRow row = findRequest(requestId).orElseThrow(() -> duplicate);
            return replayOrConflict(row, fingerprint, requestId);
        }
        FumigationView result = business.get();
        jdbc.update("UPDATE fumigation_request SET fumigation_id = ?, response_body = ?"
                + " WHERE request_id = ?", result.id(), toJson(result), requestId);
        return result;
    }

    private FumigationView replayOrConflict(RequestRow row, String fingerprint, String requestId) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已存在但业务字段不一致: " + requestId);
        }
        try {
            return objectMapper.readValue(row.responseBody(), FumigationView.class).withReplayed(true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("冻结结果反序列化失败: " + requestId, e);
        }
    }

    private GasReadingResponse replayGas(GasRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已存在但业务字段不一致");
        }
        return new GasReadingResponse("RECORDED", row.id(), row.seq(), true);
    }

    private void recordLateGasReading(FumigationRow row, GasReadingRequest request) {
        String payload = "fumigationId=" + row.id() + ", seq=" + request.seq()
                + ", concentration=" + request.concentration() + ", measuredAt=" + request.measuredAt();
        jdbc.update("INSERT INTO late_event (silo_id, request_id, event_type, payload)"
                + " VALUES (?, ?, ?, ?)", row.siloId(), request.requestId(), "LATE_GAS_READING", payload);
    }

    private void checkVersion(FumigationRow row, int expectedVersion) {
        if (row.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "版本不一致：期望 " + expectedVersion + "，当前 " + row.version());
        }
    }

    /** 熏蒸窗（UTC）与通风窗（Asia/Shanghai）换算后按半开区间互斥。 */
    private void ensureNoVentilationWindowConflict(long siloId, Instant start, Instant end) {
        List<WindowRow> windows = jdbc.query(
                "SELECT window_start, window_end FROM ventilation_job WHERE silo_id = ?",
                (rs, rowNum) -> new WindowRow(rs.getTimestamp("window_start").toLocalDateTime(),
                        rs.getTimestamp("window_end").toLocalDateTime()),
                siloId);
        for (WindowRow window : windows) {
            Instant jobStart = window.start().atZone(VENTILATION_ZONE).toInstant();
            Instant jobEnd = window.end().atZone(VENTILATION_ZONE).toInstant();
            if (jobStart.isBefore(end) && start.isBefore(jobEnd)) {
                throw ApiException.unprocessable("WINDOW_CONFLICT",
                        "作业窗与既有通风作业窗重叠（半开区间端点相接不冲突）");
            }
        }
    }

    /** 同仓熏蒸窗之间按半开区间互斥（excludeId 用于排除自身，创建时传 null）。 */
    private void ensureNoFumigationWindowConflict(long siloId, Long excludeId, Instant start, Instant end) {
        List<FumigationRow> rows = jdbc.query("SELECT * FROM fumigation WHERE silo_id = ?",
                FumigationService::mapRow, siloId);
        for (FumigationRow row : rows) {
            if (excludeId != null && row.id() == excludeId) {
                continue;
            }
            if (row.windowStart().isBefore(end) && start.isBefore(row.windowEnd())) {
                throw ApiException.unprocessable("WINDOW_CONFLICT",
                        "作业窗与既有熏蒸作业窗重叠（半开区间端点相接不冲突）");
            }
        }
    }

    private int countApprovals(long fumigationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fumigation_approval WHERE fumigation_id = ?",
                Integer.class, fumigationId);
        return count == null ? 0 : count;
    }

    private FumigationRow lockById(long fumigationId) {
        List<FumigationRow> rows = jdbc.query("SELECT * FROM fumigation WHERE id = ? FOR UPDATE",
                FumigationService::mapRow, fumigationId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + fumigationId);
        }
        return rows.get(0);
    }

    private SiloRow lockSilo(long siloId) {
        List<SiloRow> rows = jdbc.query("SELECT id, status FROM silo WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new SiloRow(rs.getLong("id"), rs.getString("status")), siloId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("SILO_NOT_FOUND", "粮仓不存在: " + siloId);
        }
        return rows.get(0);
    }

    private Optional<FumigationRow> findById(long fumigationId) {
        return jdbc.query("SELECT * FROM fumigation WHERE id = ?",
                FumigationService::mapRow, fumigationId).stream().findFirst();
    }

    private Optional<RequestRow> findRequest(String requestId) {
        return jdbc.query("SELECT * FROM fumigation_request WHERE request_id = ?",
                (rs, rowNum) -> new RequestRow(rs.getString("request_id"), rs.getString("fingerprint"),
                        rs.getString("response_body")),
                requestId).stream().findFirst();
    }

    private Optional<GasRow> findGasByRequestId(String requestId) {
        return jdbc.query("SELECT id, seq, fingerprint FROM gas_reading WHERE request_id = ?",
                (rs, rowNum) -> new GasRow(rs.getLong("id"), rs.getInt("seq"),
                        rs.getString("fingerprint")),
                requestId).stream().findFirst();
    }

    private Optional<Long> findLateEvent(String requestId) {
        return jdbc.query("SELECT id FROM late_event WHERE request_id = ?",
                (rs, rowNum) -> rs.getLong("id"), requestId).stream().findFirst();
    }

    private FumigationView toView(FumigationRow row, boolean replayed) {
        List<String> approvers = jdbc.query(
                "SELECT approver FROM fumigation_approval WHERE fumigation_id = ? ORDER BY id",
                (rs, rowNum) -> rs.getString("approver"), row.id());
        return new FumigationView(row.id(), row.siloId(), row.windowStart(), row.windowEnd(),
                row.safetyThreshold(), parseSteps(row.planSteps()), row.status(), row.version(),
                row.frozenVersion(), row.fanId(), approvers, row.releaseReviewer1(),
                row.startedAt(), row.aerationStartedAt(), row.releasedAt(), replayed);
    }

    private List<String> parseSteps(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("方案步骤反序列化失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("结果序列化失败", e);
        }
    }

    private static LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime utcDateTime) {
        return utcDateTime.toInstant(ZoneOffset.UTC);
    }

    private static FumigationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FumigationRow(rs.getLong("id"), rs.getLong("silo_id"),
                toInstant(rs.getTimestamp("window_start").toLocalDateTime()),
                toInstant(rs.getTimestamp("window_end").toLocalDateTime()),
                rs.getBigDecimal("safety_threshold"), rs.getString("plan_steps"),
                FumigationStatus.valueOf(rs.getString("status")), rs.getInt("version"),
                rs.getObject("frozen_version", Integer.class), rs.getObject("fan_id", Long.class),
                rs.getString("prev_silo_status"), rs.getString("release_reviewer1"),
                toInstantOrNull(rs.getTimestamp("started_at")),
                toInstantOrNull(rs.getTimestamp("aeration_started_at")),
                toInstantOrNull(rs.getTimestamp("released_at")));
    }

    private static Instant toInstantOrNull(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : toInstant(timestamp.toLocalDateTime());
    }

    private record SiloRow(long id, String status) {
    }

    private record WindowRow(LocalDateTime start, LocalDateTime end) {
    }

    private record RequestRow(String requestId, String fingerprint, String responseBody) {
    }

    private record GasRow(long id, int seq, String fingerprint) {
    }

    private record GasPoint(int seq, BigDecimal concentration, LocalDateTime measuredAt) {
    }

    private record FumigationRow(long id, long siloId, Instant windowStart, Instant windowEnd,
                                 BigDecimal safetyThreshold, String planSteps,
                                 FumigationStatus status, int version, Integer frozenVersion,
                                 Long fanId, String prevSiloStatus, String releaseReviewer1,
                                 Instant startedAt, Instant aerationStartedAt, Instant releasedAt) {
    }
}
