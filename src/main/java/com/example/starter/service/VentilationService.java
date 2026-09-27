package com.example.starter.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.dto.GrainDtos.CreateJobRequest;
import com.example.starter.dto.GrainDtos.JobView;
import com.example.starter.dto.GrainDtos.RecheckRequest;
import com.example.starter.dto.GrainDtos.RecheckView;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.error.ApiException;
import com.example.starter.model.JobStatus;
import com.example.starter.model.RecheckDecision;
import com.example.starter.model.SiloStatus;
import com.example.starter.model.VentilationPurpose;
import com.example.starter.util.Fingerprints;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通风作业服务：作业下达（幂等、作业窗半开占用、风机原子锁定）、完成、复测与归档。
 */
@Service
public class VentilationService {

    /** 通风最多两轮，两轮复测仍超标转人工处置 */
    static final int MAX_ROUNDS = 2;

    private final JdbcTemplate jdbc;
    private final SiloService siloService;

    public VentilationService(JdbcTemplate jdbc, SiloService siloService) {
        this.jdbc = jdbc;
        this.siloService = siloService;
    }

    /**
     * 下达通风作业。同一仓同时刻只允许一个执行中作业；作业窗按半开区间 [start, end) 占用；
     * 外界湿度高于粮堆平衡湿度禁止通风（恰等允许）；通风开始与风机锁定在同一事务内完成。
     */
    @Transactional
    public JobView createJob(long siloId, CreateJobRequest request) {
        if (!request.windowStart().isBefore(request.windowEnd())) {
            throw ApiException.badRequest("INVALID_WINDOW", "作业窗起点必须早于终点");
        }
        String fingerprint = Fingerprints.of(request.requestId(), siloId, request.fanId(),
                request.purpose(), request.windowStart(), request.windowEnd(), request.outsideHumidity());

        Optional<JobRow> existing = findJobByRequestId(request.requestId());
        if (existing.isPresent()) {
            JobRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toView(row, true);
        }

        // 锁定粮仓行，串行化同一仓的并发下达
        SiloRow silo = lockSilo(siloId);

        // 持锁后复检幂等键，覆盖并发下同键请求在锁前检查落空的情形
        Optional<JobRow> rechecked = findJobByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            JobRow row = rechecked.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toView(row, true);
        }
        SiloStatus status = SiloStatus.valueOf(silo.status());
        if (status == SiloStatus.ARCHIVED) {
            throw ApiException.conflict("SILO_ARCHIVED", "粮仓已归档，禁止下达通风作业");
        }
        if (status == SiloStatus.MANUAL_HANDLING) {
            throw ApiException.conflict("MANUAL_HANDLING_REQUIRED", "两轮通风后梯度仍超标，已转人工处置");
        }
        if (status == SiloStatus.VENTILATING) {
            throw ApiException.conflict("JOB_ALREADY_EXECUTING", "同一仓同时刻只允许一个执行中通风作业");
        }
        if (status == SiloStatus.FUMIGATING || status == SiloStatus.AERATING) {
            throw ApiException.conflict("SILO_LOCKED_BY_FUMIGATION", "粮仓熏蒸锁仓中，禁止普通通风");
        }
        if (status != SiloStatus.ALARMING) {
            throw ApiException.conflict("NO_ACTIVE_ALARM", "粮仓当前状态不允许通风: " + status);
        }
        if (request.outsideHumidity().compareTo(silo.equilibriumHumidity()) > 0) {
            throw ApiException.unprocessable("VENTILATION_FORBIDDEN_HUMIDITY",
                    "外界湿度高于粮堆平衡湿度，禁止通风");
        }

        int round = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_job WHERE silo_id = ?", Integer.class, siloId) + 1;
        if (round > MAX_ROUNDS) {
            throw ApiException.conflict("VENTILATION_ROUND_EXCEEDED", "通风最多两轮，已达上限");
        }

        Integer conflicts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_job WHERE silo_id = ? AND window_start < ? AND ? < window_end",
                Integer.class, siloId, request.windowEnd(), request.windowStart());
        if (conflicts != null && conflicts > 0) {
            throw ApiException.conflict("WINDOW_CONFLICT", "作业窗与既有作业重叠（半开区间端点相接不冲突）");
        }

        // 与同仓未终结熏蒸窗半开互斥：熏蒸窗按裸 UTC 墙钟存储，读为 UTC 瞬时再转 Asia/Shanghai 墙钟比较
        List<java.time.Instant[]> fumigationWindows = jdbc.query(
                "SELECT window_start, window_end FROM fumigation_order WHERE silo_id = ? AND status <> 'RELEASED'",
                (rs, rowNum) -> new java.time.Instant[] {
                        rs.getTimestamp("window_start").toLocalDateTime()
                                .toInstant(java.time.ZoneOffset.UTC),
                        rs.getTimestamp("window_end").toLocalDateTime()
                                .toInstant(java.time.ZoneOffset.UTC)},
                siloId);
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        for (java.time.Instant[] window : fumigationWindows) {
            java.time.LocalDateTime otherStart = java.time.LocalDateTime.ofInstant(window[0], zone);
            java.time.LocalDateTime otherEnd = java.time.LocalDateTime.ofInstant(window[1], zone);
            if (otherStart.isBefore(request.windowEnd()) && request.windowStart().isBefore(otherEnd)) {
                throw ApiException.conflict("WINDOW_CONFLICT",
                        "作业窗与既有熏蒸作业窗重叠（半开区间端点相接不冲突）");
            }
        }

        // 风机存在性校验
        siloService.getFan(request.fanId());

        long jobId;
        try {
            jobId = siloService.insert(
                    "INSERT INTO ventilation_job (silo_id, fan_id, request_id, purpose, window_start, window_end,"
                            + " outside_humidity, round_no, fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    siloId, request.fanId(), request.requestId(), request.purpose().name(),
                    request.windowStart(), request.windowEnd(), request.outsideHumidity(), round, fingerprint);
        } catch (DuplicateKeyException duplicate) {
            // 跨仓并发下同键请求落入唯一约束：按幂等重放或键冲突处理
            JobRow row = findJobByRequestId(request.requestId()).orElseThrow(() -> duplicate);
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toView(row, true);
        }

        // 风机锁定与作业开始同事务：锁定失败则整体回滚，不得作业开始而风机未锁
        int locked = jdbc.update(
                "UPDATE fan SET status = 'LOCKED', locked_job_id = ? WHERE id = ? AND status = 'IDLE'",
                jobId, request.fanId());
        if (locked == 0) {
            throw ApiException.conflict("FAN_BUSY", "风机已被占用: " + request.fanId());
        }
        jdbc.update("UPDATE silo SET status = 'VENTILATING' WHERE id = ?", siloId);
        return toView(findJobById(jobId).orElseThrow(), false);
    }

    /**
     * 完成通风作业：释放风机，粮仓转入效果复测。
     */
    @Transactional
    public JobView completeJob(long jobId) {
        JobRow job = findJobById(jobId).orElseThrow(
                () -> ApiException.notFound("JOB_NOT_FOUND", "通风作业不存在: " + jobId));
        if (job.status() != JobStatus.EXECUTING) {
            throw ApiException.conflict("INVALID_JOB_STATE", "仅执行中作业可完成，当前: " + job.status());
        }
        jdbc.update("UPDATE ventilation_job SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", jobId);
        jdbc.update("UPDATE fan SET status = 'IDLE', locked_job_id = NULL WHERE locked_job_id = ?", jobId);
        jdbc.update("UPDATE silo SET status = 'RECHECKING' WHERE id = ?", job.siloId());
        return toView(findJobById(jobId).orElseThrow(), false);
    }

    /**
     * 效果复测：梯度超过限值判超标（恰等合格）；超标且未满两轮允许二次通风，
     * 两轮仍超标转人工处置；requestId 幂等。
     */
    @Transactional
    public RecheckView recheck(long jobId, RecheckRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), jobId, request.gradient(),
                request.gradientLimit(), request.measuredAt());

        Optional<RecheckRow> existing = findRecheckByRequestId(request.requestId());
        if (existing.isPresent()) {
            RecheckRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toView(row, true);
        }

        JobRow job = findJobById(jobId).orElseThrow(
                () -> ApiException.notFound("JOB_NOT_FOUND", "通风作业不存在: " + jobId));
        if (job.status() != JobStatus.COMPLETED) {
            throw ApiException.conflict("INVALID_JOB_STATE", "仅已完成作业可复测，当前: " + job.status());
        }

        boolean exceeded = request.gradient().compareTo(request.gradientLimit()) > 0;
        RecheckDecision decision;
        if (!exceeded) {
            decision = RecheckDecision.PASSED;
        } else if (job.roundNo() < MAX_ROUNDS) {
            decision = RecheckDecision.SECOND_VENTILATION;
        } else {
            decision = RecheckDecision.MANUAL_HANDLING;
        }

        long recheckId;
        try {
            recheckId = siloService.insert(
                    "INSERT INTO recheck (job_id, silo_id, request_id, gradient, gradient_limit, exceeded, decision,"
                            + " fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    jobId, job.siloId(), request.requestId(), request.gradient(), request.gradientLimit(),
                    exceeded, decision.name(), fingerprint);
        } catch (DuplicateKeyException duplicate) {
            RecheckRow row = findRecheckByRequestId(request.requestId()).orElseThrow(() -> duplicate);
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toView(row, true);
        }

        if (decision == RecheckDecision.SECOND_VENTILATION) {
            jdbc.update("UPDATE silo SET status = 'ALARMING' WHERE id = ?", job.siloId());
        } else if (decision == RecheckDecision.MANUAL_HANDLING) {
            jdbc.update("UPDATE silo SET status = 'MANUAL_HANDLING' WHERE id = ?", job.siloId());
        }
        return toView(findRecheckById(recheckId).orElseThrow(), false);
    }

    /**
     * 归档作业：终态。要求作业已完成且复测合格；重复归档返回幂等成功；
     * 归档后粮仓进入终态，迟到温湿度数据只记迟到事件。
     */
    @Transactional
    public JobView archive(long jobId) {
        JobRow job = findJobById(jobId).orElseThrow(
                () -> ApiException.notFound("JOB_NOT_FOUND", "通风作业不存在: " + jobId));
        if (job.status() == JobStatus.ARCHIVED) {
            return toView(job, true);
        }
        if (job.status() != JobStatus.COMPLETED) {
            throw ApiException.conflict("INVALID_JOB_STATE", "仅已完成作业可归档，当前: " + job.status());
        }
        Integer passed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recheck WHERE job_id = ? AND decision = 'PASSED'",
                Integer.class, jobId);
        if (passed == null || passed == 0) {
            throw ApiException.conflict("RECHECK_NOT_PASSED", "复测未合格，禁止归档");
        }
        jdbc.update("UPDATE ventilation_job SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", jobId);
        jdbc.update("UPDATE silo SET status = 'ARCHIVED' WHERE id = ?", job.siloId());
        return toView(findJobById(jobId).orElseThrow(), false);
    }

    /**
     * 粮仓作业窗列表（半开区间 [start, end)），可复算占用。
     */
    public List<JobView> windows(long siloId) {
        siloService.getSilo(siloId);
        return jdbc.query("SELECT * FROM ventilation_job WHERE silo_id = ? ORDER BY window_start, id",
                VentilationService::mapJob, siloId).stream().map(row -> toView(row, false)).toList();
    }

    /**
     * 粮仓全部复测记录（含轮次），两轮复测对比可复算。
     */
    public List<RecheckView> rechecksOfSilo(long siloId) {
        siloService.getSilo(siloId);
        return jdbc.query("SELECT r.*, j.round_no AS j_round FROM recheck r"
                        + " JOIN ventilation_job j ON j.id = r.job_id WHERE r.silo_id = ? ORDER BY r.id",
                (rs, rowNum) -> toView(mapRecheck(rs, rowNum), false), siloId);
    }

    /**
     * 按 ID 查询作业。
     */
    public JobView getJob(long jobId) {
        return toView(findJobById(jobId).orElseThrow(
                () -> ApiException.notFound("JOB_NOT_FOUND", "通风作业不存在: " + jobId)), false);
    }

    private SiloRow lockSilo(long siloId) {
        List<SiloRow> rows = jdbc.query(
                "SELECT id, status, equilibrium_humidity FROM silo WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new SiloRow(rs.getLong("id"), rs.getString("status"),
                        rs.getBigDecimal("equilibrium_humidity")),
                siloId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("SILO_NOT_FOUND", "粮仓不存在: " + siloId);
        }
        return rows.get(0);
    }

    private Optional<JobRow> findJobByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM ventilation_job WHERE request_id = ?",
                VentilationService::mapJob, requestId).stream().findFirst();
    }

    private Optional<JobRow> findJobById(long jobId) {
        return jdbc.query("SELECT * FROM ventilation_job WHERE id = ?",
                VentilationService::mapJob, jobId).stream().findFirst();
    }

    private Optional<RecheckRow> findRecheckByRequestId(String requestId) {
        return jdbc.query("SELECT r.*, j.round_no AS j_round FROM recheck r"
                        + " JOIN ventilation_job j ON j.id = r.job_id WHERE r.request_id = ?",
                (rs, rowNum) -> mapRecheck(rs, rowNum), requestId).stream().findFirst();
    }

    private Optional<RecheckRow> findRecheckById(long recheckId) {
        return jdbc.query("SELECT r.*, j.round_no AS j_round FROM recheck r"
                        + " JOIN ventilation_job j ON j.id = r.job_id WHERE r.id = ?",
                (rs, rowNum) -> mapRecheck(rs, rowNum), recheckId).stream().findFirst();
    }

    private static JobRow mapJob(ResultSet rs, int rowNum) throws SQLException {
        return new JobRow(rs.getLong("id"), rs.getLong("silo_id"), rs.getLong("fan_id"),
                VentilationPurpose.valueOf(rs.getString("purpose")),
                rs.getTimestamp("window_start").toLocalDateTime(),
                rs.getTimestamp("window_end").toLocalDateTime(),
                rs.getBigDecimal("outside_humidity"), rs.getInt("round_no"),
                JobStatus.valueOf(rs.getString("status")), rs.getString("fingerprint"));
    }

    private static RecheckRow mapRecheck(ResultSet rs, int rowNum) throws SQLException {
        return new RecheckRow(rs.getLong("id"), rs.getLong("job_id"), rs.getLong("silo_id"),
                rs.getInt("j_round"), rs.getBigDecimal("gradient"), rs.getBigDecimal("gradient_limit"),
                rs.getBoolean("exceeded"), RecheckDecision.valueOf(rs.getString("decision")),
                rs.getString("fingerprint"));
    }

    private static JobView toView(JobRow row, boolean replayed) {
        return new JobView(row.id(), row.siloId(), row.fanId(), row.purpose(), row.windowStart(),
                row.windowEnd(), row.outsideHumidity(), row.roundNo(), row.status(), replayed);
    }

    private static RecheckView toView(RecheckRow row, boolean replayed) {
        return new RecheckView(row.id(), row.jobId(), row.siloId(), row.roundNo(), row.gradient(),
                row.gradientLimit(), row.exceeded(), row.decision(), replayed);
    }

    private record SiloRow(long id, String status, java.math.BigDecimal equilibriumHumidity) {
    }

    private record JobRow(long id, long siloId, long fanId, VentilationPurpose purpose,
                          LocalDateTime windowStart, LocalDateTime windowEnd,
                          java.math.BigDecimal outsideHumidity, int roundNo, JobStatus status,
                          String fingerprint) {
    }

    private record RecheckRow(long id, long jobId, long siloId, int roundNo,
                              java.math.BigDecimal gradient, java.math.BigDecimal gradientLimit,
                              boolean exceeded, RecheckDecision decision, String fingerprint) {
    }
}
