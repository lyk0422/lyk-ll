package com.example.starter.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.CreateFumigationRequest;
import com.example.starter.dto.FumigationDtos.FinishRequest;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.ReleaseRequest;
import com.example.starter.dto.FumigationDtos.SampleRequest;
import com.example.starter.dto.FumigationDtos.SampleView;
import com.example.starter.dto.FumigationDtos.StartRequest;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;
import com.example.starter.util.Fingerprints;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 熏蒸审批与散气解除联锁服务：申请、双人批准、原子锁仓开始、结束转散气绑定风机、
 * 合成气体读数采样、双复核人解除。所有写操作 requestId 幂等（结构化指纹），
 * 业务变更、版本递增与幂等记录同事务；业务失败整体回滚，不占幂等键。
 */
@Service
public class FumigationService {

    /** 解除条件：连续三点安全读数的首末最小间隔（分钟），恰等合法 */
    static final long RELEASE_MIN_SPAN_MINUTES = 30;

    /** 解除条件：连续安全读数点数 */
    static final int RELEASE_REQUIRED_POINTS = 3;

    /** 通风作业窗时区（既有普通通风使用本地时间） */
    private static final ZoneId VENTILATION_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final SiloService siloService;
    private final ObjectMapper objectMapper;

    public FumigationService(JdbcTemplate jdbc, SiloService siloService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.siloService = siloService;
        this.objectMapper = objectMapper;
    }

    /**
     * 申请熏蒸单：作业窗 UTC 半开区间，与同仓普通通风窗及未解除熏蒸窗互斥（端点相接可共存）；
     * 版本从 1 开始；requestId 幂等，同键同参重放首次冻结结果，异参 409。
     */
    @Transactional
    public FumigationView create(long siloId, CreateFumigationRequest request) {
        if (!request.windowStart().isBefore(request.windowEnd())) {
            throw ApiException.badRequest("INVALID_WINDOW", "熏蒸作业窗起点必须早于终点");
        }
        String fingerprint = Fingerprints.structured("CREATE", request.requestId(), siloId,
                request.windowStart(), request.windowEnd(), request.safeThreshold());

        Optional<FumigationView> replayed = replayIfPresent(request.requestId(), fingerprint,
                FumigationView.class, FumigationView::asReplayed);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        // 锁定粮仓行，串行化同仓并发申请
        SiloRow silo = lockSilo(siloId);
        Optional<FumigationView> rechecked = replayIfPresent(request.requestId(), fingerprint,
                FumigationView.class, FumigationView::asReplayed);
        if (rechecked.isPresent()) {
            return rechecked.get();
        }

        SiloStatus siloStatus = SiloStatus.valueOf(silo.status());
        if (siloStatus != SiloStatus.MONITORING && siloStatus != SiloStatus.ALARMING) {
            throw ApiException.unprocessable("SILO_STATE_FORBIDDEN",
                    "粮仓当前状态不允许申请熏蒸: " + siloStatus);
        }

        // 与普通通风窗互斥：通风窗为 Asia/Shanghai 本地时间，熏蒸窗为 UTC，换算后按半开区间比较
        LocalDateTime localStart = LocalDateTime.ofInstant(request.windowStart(), VENTILATION_ZONE);
        LocalDateTime localEnd = LocalDateTime.ofInstant(request.windowEnd(), VENTILATION_ZONE);
        Integer ventilationConflicts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_job WHERE silo_id = ? AND window_start < ? AND ? < window_end",
                Integer.class, siloId, localEnd, localStart);
        if (ventilationConflicts != null && ventilationConflicts > 0) {
            throw ApiException.unprocessable("WINDOW_CONFLICT",
                    "熏蒸作业窗与同仓普通通风窗重叠（半开区间端点相接不冲突）");
        }

        // 与同仓未解除熏蒸窗互斥（同为 UTC，直接比较）
        Integer fumigationConflicts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fumigation_order WHERE silo_id = ? AND status <> 'RELEASED'"
                        + " AND window_start < ? AND ? < window_end",
                Integer.class, siloId, toTs(request.windowEnd()), toTs(request.windowStart()));
        if (fumigationConflicts != null && fumigationConflicts > 0) {
            throw ApiException.unprocessable("WINDOW_CONFLICT",
                    "熏蒸作业窗与同仓既有熏蒸窗重叠（半开区间端点相接不冲突）");
        }

        long id;
        try {
            id = siloService.insert(
                    "INSERT INTO fumigation_order (silo_id, request_id, window_start, window_end,"
                            + " safe_threshold, fingerprint) VALUES (?, ?, ?, ?, ?, ?)",
                    siloId, request.requestId(), toTs(request.windowStart()), toTs(request.windowEnd()),
                    request.safeThreshold(), fingerprint);
        } catch (DuplicateKeyException duplicate) {
            // 跨仓并发下同键请求落入唯一约束：按幂等重放或键冲突处理
            return replayAfterDuplicate(request.requestId(), fingerprint,
                    FumigationView.class, FumigationView::asReplayed, duplicate);
        }
        Optional<FumigationView> occupied = occupyKey(request.requestId(), "CREATE", id, fingerprint,
                FumigationView.class, FumigationView::asReplayed);
        if (occupied.isPresent()) {
            return occupied.get();
        }

        FumigationView view = toView(findOrderById(id).orElseThrow(), false);
        freezeResponse(request.requestId(), view);
        return view;
    }

    /**
     * 批准：两名不同审批人共同批准；expectedVersion 不匹配 409；同一审批人重复批准 422。
     */
    @Transactional
    public FumigationView approve(long fumigationId, ApproveRequest request) {
        String fingerprint = Fingerprints.structured("APPROVE", request.requestId(), fumigationId,
                request.expectedVersion(), request.approver(), request.decidedAt());

        Optional<FumigationView> replayed = replayIfPresent(request.requestId(), fingerprint,
                FumigationView.class, FumigationView::asReplayed);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        FumigationRow order = lockOrder(fumigationId);
        Optional<FumigationView> rechecked = replayIfPresent(request.requestId(), fingerprint,
                FumigationView.class, FumigationView::asReplayed);
        if (rechecked.isPresent()) {
            return rechecked.get();
        }
        Optional<FumigationView> occupied = occupyKey(request.requestId(), "APPROVE", fumigationId,
                fingerprint, FumigationView.class, FumigationView::asReplayed);
        if (occupied.isPresent()) {
            return occupied.get();
        }

        checkVersion(order, request.expectedVersion());
        if (order.status() != FumigationStatus.PENDING_APPROVAL) {
            throw ApiException.unprocessable("INVALID_FUMIGATION_STATE",
                    "仅待批准熏蒸单可批准，当前: " + order.status());
        }
        Integer already = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fumigation_approval WHERE fumigation_id = ? AND approver = ?",
                Integer.class, fumigationId, request.approver());
        if (already != null && already > 0) {
            throw ApiException.unprocessable("APPROVER_DUPLICATED",
                    "审批人已批准过本单，须两名不同审批人: " + request.approver());
        }

        jdbc.update("INSERT INTO fumigation_approval (fumigation_id, approver, request_id, decided_at)"
                        + " VALUES (?, ?, ?, ?)",
                fumigationId, request.approver(), request.requestId(), toTs(request.decidedAt()));
        int approverCount = order.approverCount() + 1;
        String nextStatus = approverCount >= 2
                ? FumigationStatus.APPROVED.name() : FumigationStatus.PENDING_APPROVAL.name();
        jdbc.update("UPDATE fumigation_order SET status = ?, approver_count = ?, version = version + 1"
                + " WHERE id = ?", nextStatus, approverCount, fumigationId);

        FumigationView view = toView(findOrderById(fumigationId).orElseThrow(), false);
        freezeResponse(request.requestId(), view);
        return view;
    }

    /**
     * 开始熏蒸：原子锁仓并冻结方案版本；实际开始时再查无执行中通风；
     * 此后禁止人员进入与普通通风（粮仓进入 FUMIGATING）。
     */
    @Transactional
    public FumigationView start(long fumigationId, StartRequest request) {
        String fingerprint = Fingerprints.structured("START", request.requestId(), fumigationId,
                request.expectedVersion(), request.startedAt());

        Optional<FumigationView> replayed = replayIfPresent(request.requestId(), fingerprint,
                FumigationView.class, FumigationView::asReplayed);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        FumigationRow order = lockOrder(fumigationId);
        Optional<FumigationView> rechecked = replayIfPresent(request.requestId(), fingerprint,
                FumigationView.class, FumigationView::asReplayed);
        if (rechecked.isPresent()) {
            return rechecked.get();
        }
        Optional<FumigationView> occupied = occupyKey(request.requestId(), "START", fumigationId,
                fingerprint, FumigationView.class, FumigationView::asReplayed);
        if (occupied.isPresent()) {
            return occupied.get();
        }

        checkVersion(order, request.expectedVersion());
        if (order.status() != FumigationStatus.APPROVED) {
            throw ApiException.unprocessable("INVALID_FUMIGATION_STATE",
                    "仅已批准熏蒸单可开始，当前: " + order.status());
        }

        SiloRow silo = lockSilo(order.siloId());
        // 实际开始时再查无执行中通风
        Integer executing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_job WHERE silo_id = ? AND status = 'EXECUTING'",
                Integer.class, order.siloId());
        if (executing != null && executing > 0) {
            throw ApiException.unprocessable("VENTILATION_EXECUTING",
                    "存在执行中普通通风，禁止开始熏蒸");
        }
        SiloStatus siloStatus = SiloStatus.valueOf(silo.status());
        if (siloStatus != SiloStatus.MONITORING && siloStatus != SiloStatus.ALARMING) {
            throw ApiException.unprocessable("SILO_STATE_FORBIDDEN",
                    "粮仓当前状态不允许开始熏蒸: " + siloStatus);
        }

        jdbc.update("UPDATE fumigation_order SET status = 'FUMIGATING', version = version + 1,"
                        + " prior_silo_status = ?, frozen_plan_version = ?, started_at = ? WHERE id = ?",
                siloStatus.name(), order.version(), toTs(request.startedAt()), fumigationId);
        jdbc.update("UPDATE silo SET status = 'FUMIGATING' WHERE id = ?", order.siloId());

        FumigationView view = toView(findOrderById(fumigationId).orElseThrow(), false);
        freezeResponse(request.requestId(), view);
        return view;
    }

    /**
     * 结束熏蒸转散气：绑定一台空闲风机（沿用唯一资源占用规则，原子锁定）；
     * 锁风机失败则整体回滚，不进入散气。
     */
    @Transactional
    public FumigationView finish(long fumigationId, FinishRequest request) {
        String fingerprint = Fingerprints.structured("FINISH", request.requestId(), fumigationId,
                request.expectedVersion(), request.fanId(), request.finishedAt());

        Optional<FumigationView> replayed = replayIfPresent(request.requestId(), fingerprint,
                FumigationView.class, FumigationView::asReplayed);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        FumigationRow order = lockOrder(fumigationId);
        Optional<FumigationView> rechecked = replayIfPresent(request.requestId(), fingerprint,
                FumigationView.class, FumigationView::asReplayed);
        if (rechecked.isPresent()) {
            return rechecked.get();
        }
        Optional<FumigationView> occupied = occupyKey(request.requestId(), "FINISH", fumigationId,
                fingerprint, FumigationView.class, FumigationView::asReplayed);
        if (occupied.isPresent()) {
            return occupied.get();
        }

        checkVersion(order, request.expectedVersion());
        if (order.status() != FumigationStatus.FUMIGATING) {
            throw ApiException.unprocessable("INVALID_FUMIGATION_STATE",
                    "仅熏蒸中熏蒸单可结束转散气，当前: " + order.status());
        }

        // 风机存在性校验
        siloService.getFan(request.fanId());

        // 风机锁定与转散气同事务：锁定失败则整体回滚
        int locked = jdbc.update(
                "UPDATE fan SET status = 'LOCKED', locked_fumigation_id = ? WHERE id = ? AND status = 'IDLE'",
                fumigationId, request.fanId());
        if (locked == 0) {
            throw ApiException.unprocessable("FAN_BUSY", "风机已被占用: " + request.fanId());
        }
        jdbc.update("UPDATE fumigation_order SET status = 'AERATING', version = version + 1,"
                        + " fan_id = ?, finished_at = ? WHERE id = ?",
                request.fanId(), toTs(request.finishedAt()), fumigationId);
        jdbc.update("UPDATE silo SET status = 'AERATING' WHERE id = ?", order.siloId());

        FumigationView view = toView(findOrderById(fumigationId).orElseThrow(), false);
        freezeResponse(request.requestId(), view);
        return view;
    }

    /**
     * 散气采样：读数以单号+序号唯一，时刻严格递增，浓度为有限非负小数；
     * 解除（关闭）后读数只记迟到事件，不改已冻结证据；requestId 幂等。
     */
    @Transactional
    public SampleView sample(long fumigationId, SampleRequest request) {
        if (request.concentration().signum() < 0) {
            throw ApiException.badRequest("INVALID_CONCENTRATION", "浓度须为有限非负小数");
        }
        String fingerprint = Fingerprints.structured("SAMPLE", request.requestId(), fumigationId,
                request.seqNo(), request.concentration(), request.measuredAt());

        Optional<SampleView> replayed = replayIfPresent(request.requestId(), fingerprint,
                SampleView.class, SampleView::asReplayed);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        // 锁定熏蒸单行，与解除串行化：高浓度补录与解除竞争不得用旧三点放行
        FumigationRow order = lockOrder(fumigationId);
        Optional<SampleView> rechecked = replayIfPresent(request.requestId(), fingerprint,
                SampleView.class, SampleView::asReplayed);
        if (rechecked.isPresent()) {
            return rechecked.get();
        }
        Optional<SampleView> occupied = occupyKey(request.requestId(), "SAMPLE", fumigationId,
                fingerprint, SampleView.class, SampleView::asReplayed);
        if (occupied.isPresent()) {
            return occupied.get();
        }

        if (order.status() == FumigationStatus.RELEASED) {
            SampleView late = recordLateReading(order, request);
            freezeResponse(request.requestId(), late);
            return late;
        }
        if (order.status() != FumigationStatus.AERATING) {
            throw ApiException.unprocessable("INVALID_FUMIGATION_STATE",
                    "仅散气中可上报气体读数，当前: " + order.status());
        }

        LocalDateTime measuredAt = toTs(request.measuredAt());
        List<LocalDateTime> maxTimes = jdbc.query(
                "SELECT MAX(measured_at) AS max_at FROM fumigation_reading WHERE fumigation_id = ?",
                (rs, rowNum) -> rs.getTimestamp("max_at") == null
                        ? null : rs.getTimestamp("max_at").toLocalDateTime(),
                fumigationId);
        LocalDateTime maxAt = maxTimes.isEmpty() ? null : maxTimes.get(0);
        if (maxAt != null && !measuredAt.isAfter(maxAt)) {
            throw ApiException.unprocessable("READING_TIME_NOT_INCREASING",
                    "采样时刻须严格递增，当前最大时刻: " + maxAt);
        }

        Integer seqUsed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fumigation_reading WHERE fumigation_id = ? AND seq_no = ?",
                Integer.class, fumigationId, request.seqNo());
        if (seqUsed != null && seqUsed > 0) {
            throw ApiException.conflict("READING_SEQ_CONFLICT",
                    "读数序号已占用（以单号+序号唯一）: " + request.seqNo());
        }

        long readingId;
        try {
            readingId = siloService.insert(
                    "INSERT INTO fumigation_reading (fumigation_id, request_id, seq_no, concentration,"
                            + " measured_at, fingerprint) VALUES (?, ?, ?, ?, ?, ?)",
                    fumigationId, request.requestId(), request.seqNo(), request.concentration(),
                    measuredAt, fingerprint);
        } catch (DuplicateKeyException duplicate) {
            throw ApiException.conflict("READING_SEQ_CONFLICT",
                    "读数序号已占用（以单号+序号唯一）: " + request.seqNo());
        }

        SampleView view = new SampleView(readingId, fumigationId, request.seqNo(),
                request.concentration(), request.measuredAt(), false, false);
        freezeResponse(request.requestId(), view);
        return view;
    }

    /**
     * 解除：两名不同复核人最终确认，持锁重查最新读数与版本；
     * 连续三点（序号相连）≤安全阈值且首末间隔≥30分钟（恰等合法）才可解除；
     * 原子结束散气、释放仓锁与风机，失败一项均不释放。
     */
    @Transactional
    public FumigationView release(long fumigationId, ReleaseRequest request) {
        String fingerprint = Fingerprints.structured("RELEASE", request.requestId(), fumigationId,
                request.expectedVersion(), request.reviewerOne(), request.reviewerTwo(),
                request.releasedAt());

        Optional<FumigationView> replayed = replayIfPresent(request.requestId(), fingerprint,
                FumigationView.class, FumigationView::asReplayed);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        FumigationRow order = lockOrder(fumigationId);
        Optional<FumigationView> rechecked = replayIfPresent(request.requestId(), fingerprint,
                FumigationView.class, FumigationView::asReplayed);
        if (rechecked.isPresent()) {
            return rechecked.get();
        }
        Optional<FumigationView> occupied = occupyKey(request.requestId(), "RELEASE", fumigationId,
                fingerprint, FumigationView.class, FumigationView::asReplayed);
        if (occupied.isPresent()) {
            return occupied.get();
        }

        checkVersion(order, request.expectedVersion());
        if (order.status() != FumigationStatus.AERATING) {
            throw ApiException.unprocessable("INVALID_FUMIGATION_STATE",
                    "仅散气中熏蒸单可解除，当前: " + order.status());
        }
        if (request.reviewerOne().equals(request.reviewerTwo())) {
            throw ApiException.unprocessable("REVIEWER_DUPLICATED",
                    "解除须两名不同复核人最终确认");
        }

        // 持锁重查最新读数：与采样串行化，不使用旧三点放行
        List<ReadingPoint> latest = jdbc.query(
                "SELECT seq_no, concentration, measured_at FROM fumigation_reading"
                        + " WHERE fumigation_id = ? ORDER BY seq_no DESC LIMIT " + RELEASE_REQUIRED_POINTS,
                (rs, rowNum) -> new ReadingPoint(rs.getInt("seq_no"),
                        rs.getBigDecimal("concentration"),
                        rs.getTimestamp("measured_at").toLocalDateTime()),
                fumigationId);
        if (latest.size() < RELEASE_REQUIRED_POINTS) {
            throw ApiException.unprocessable("INSUFFICIENT_READINGS",
                    "安全读数不足三点，禁止解除");
        }
        ReadingPoint newest = latest.get(0);
        ReadingPoint middle = latest.get(1);
        ReadingPoint oldest = latest.get(2);
        if (newest.seqNo() - 1 != middle.seqNo() || middle.seqNo() - 1 != oldest.seqNo()) {
            throw ApiException.unprocessable("READING_SEQ_GAP",
                    "最新三点序号不相连，缺号不视为安全");
        }
        if (newest.concentration().compareTo(order.safeThreshold()) > 0
                || middle.concentration().compareTo(order.safeThreshold()) > 0
                || oldest.concentration().compareTo(order.safeThreshold()) > 0) {
            throw ApiException.unprocessable("CONCENTRATION_ABOVE_THRESHOLD",
                    "最新三点存在越限读数，连续计数已打断");
        }
        if (Duration.between(oldest.measuredAt(), newest.measuredAt())
                .compareTo(Duration.ofMinutes(RELEASE_MIN_SPAN_MINUTES)) < 0) {
            throw ApiException.unprocessable("SPAN_TOO_SHORT",
                    "最新三点首末间隔不足30分钟，禁止解除");
        }

        jdbc.update("UPDATE fumigation_order SET status = 'RELEASED', version = version + 1,"
                        + " release_reviewer_one = ?, release_reviewer_two = ?, released_at = ? WHERE id = ?",
                request.reviewerOne(), request.reviewerTwo(), toTs(request.releasedAt()), fumigationId);
        jdbc.update("UPDATE fan SET status = 'IDLE', locked_fumigation_id = NULL"
                + " WHERE locked_fumigation_id = ?", fumigationId);
        jdbc.update("UPDATE silo SET status = ? WHERE id = ?",
                order.priorSiloStatus() == null ? SiloStatus.MONITORING.name() : order.priorSiloStatus(),
                order.siloId());

        FumigationView view = toView(findOrderById(fumigationId).orElseThrow(), false);
        freezeResponse(request.requestId(), view);
        return view;
    }

    /**
     * 按 ID 查询熏蒸单（含审批人列表）。
     */
    public FumigationView getFumigation(long fumigationId) {
        return toView(findOrderById(fumigationId).orElseThrow(
                () -> ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + fumigationId)), false);
    }

    /**
     * 熏蒸单全部散气读数（按序号排序，可复算连续三点）。
     */
    public List<SampleView> readings(long fumigationId) {
        getFumigation(fumigationId);
        return jdbc.query("SELECT * FROM fumigation_reading WHERE fumigation_id = ? ORDER BY seq_no",
                (rs, rowNum) -> new SampleView(rs.getLong("id"), fumigationId, rs.getInt("seq_no"),
                        rs.getBigDecimal("concentration"),
                        rs.getTimestamp("measured_at").toLocalDateTime().toInstant(ZoneOffset.UTC),
                        false, false),
                fumigationId);
    }

    /**
     * 粮仓全部熏蒸单（含解除记录），按申请顺序。
     */
    public List<FumigationView> fumigationsOfSilo(long siloId) {
        siloService.getSilo(siloId);
        return jdbc.query("SELECT * FROM fumigation_order WHERE silo_id = ? ORDER BY id",
                FumigationService::mapOrder, siloId).stream().map(row -> toView(row, false)).toList();
    }

    private void checkVersion(FumigationRow order, int expectedVersion) {
        if (order.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "版本不匹配：期望 " + expectedVersion + "，当前 " + order.version());
        }
    }

    private SampleView recordLateReading(FumigationRow order, SampleRequest request) {
        String payload = "fumigationId=" + order.id() + ", seqNo=" + request.seqNo()
                + ", concentration=" + request.concentration() + ", measuredAt=" + request.measuredAt();
        try {
            jdbc.update("INSERT INTO late_event (silo_id, request_id, event_type, payload)"
                            + " VALUES (?, ?, ?, ?)",
                    order.siloId(), request.requestId(), "LATE_FUMIGATION_READING", payload);
        } catch (DuplicateKeyException duplicate) {
            // 迟到事件已记录：按已记录处理，不重复插入
            return new SampleView(null, order.id(), request.seqNo(), request.concentration(),
                    request.measuredAt(), true, true);
        }
        return new SampleView(null, order.id(), request.seqNo(), request.concentration(),
                request.measuredAt(), true, false);
    }

    private SiloRow lockSilo(long siloId) {
        List<SiloRow> rows = jdbc.query(
                "SELECT id, status FROM silo WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new SiloRow(rs.getLong("id"), rs.getString("status")),
                siloId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("SILO_NOT_FOUND", "粮仓不存在: " + siloId);
        }
        return rows.get(0);
    }

    private FumigationRow lockOrder(long fumigationId) {
        List<FumigationRow> rows = jdbc.query(
                "SELECT * FROM fumigation_order WHERE id = ? FOR UPDATE",
                FumigationService::mapOrder, fumigationId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + fumigationId);
        }
        return rows.get(0);
    }

    private Optional<FumigationRow> findOrderById(long fumigationId) {
        return jdbc.query("SELECT * FROM fumigation_order WHERE id = ?",
                FumigationService::mapOrder, fumigationId).stream().findFirst();
    }

    private Optional<IdemRow> findIdem(String requestId) {
        return jdbc.query("SELECT * FROM fumigation_idempotency WHERE request_id = ?",
                (rs, rowNum) -> new IdemRow(rs.getString("request_id"), rs.getString("fingerprint"),
                        rs.getString("response_body")),
                requestId).stream().findFirst();
    }

    /**
     * 幂等重放：同键同参返回首次冻结结果（标记重放），同键异参 409；无记录返回空。
     */
    private <T> Optional<T> replayIfPresent(String requestId, String fingerprint, Class<T> type,
                                            UnaryOperator<T> replayMarker) {
        Optional<IdemRow> existing = findIdem(requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        IdemRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已存在但业务字段不一致: " + requestId);
        }
        return Optional.of(replayMarker.apply(deserialize(row.responseBody(), type)));
    }

    /**
     * 占键：先插入幂等记录（响应体占位），同事务内业务失败回滚即不占键；
     * 并发同键落入唯一约束时按幂等重放或键冲突处理。
     */
    private <T> Optional<T> occupyKey(String requestId, String operation, long fumigationId,
                                      String fingerprint, Class<T> type,
                                      UnaryOperator<T> replayMarker) {
        try {
            jdbc.update("INSERT INTO fumigation_idempotency (request_id, operation, fumigation_id,"
                            + " fingerprint, response_body) VALUES (?, ?, ?, ?, ?)",
                    requestId, operation, fumigationId, fingerprint, "");
            return Optional.empty();
        } catch (DuplicateKeyException duplicate) {
            return Optional.of(replayAfterDuplicate(requestId, fingerprint, type, replayMarker,
                    duplicate));
        }
    }

    /**
     * 冻结首次成功响应：与业务变更、版本递增同事务提交，重放时原样返回。
     */
    private void freezeResponse(String requestId, Object response) {
        jdbc.update("UPDATE fumigation_idempotency SET response_body = ? WHERE request_id = ?",
                serialize(response), requestId);
    }

    private <T> T replayAfterDuplicate(String requestId, String fingerprint, Class<T> type,
                                       UnaryOperator<T> replayMarker, DuplicateKeyException duplicate) {
        return replayIfPresent(requestId, fingerprint, type, replayMarker)
                .orElseThrow(() -> duplicate);
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }

    private <T> T deserialize(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private FumigationView toView(FumigationRow row, boolean replayed) {
        List<String> approvers = jdbc.query(
                "SELECT approver FROM fumigation_approval WHERE fumigation_id = ? ORDER BY id",
                (rs, rowNum) -> rs.getString("approver"), row.id());
        return new FumigationView(row.id(), row.siloId(), row.windowStart(), row.windowEnd(),
                row.safeThreshold(), row.status(), row.version(), row.fanId(), approvers,
                row.frozenPlanVersion(), replayed);
    }

    private static LocalDateTime toTs(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static FumigationRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new FumigationRow(rs.getLong("id"), rs.getLong("silo_id"),
                rs.getTimestamp("window_start").toLocalDateTime().toInstant(ZoneOffset.UTC),
                rs.getTimestamp("window_end").toLocalDateTime().toInstant(ZoneOffset.UTC),
                rs.getBigDecimal("safe_threshold"),
                FumigationStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getObject("fan_id", Long.class),
                rs.getString("prior_silo_status"),
                rs.getObject("frozen_plan_version", Integer.class),
                rs.getInt("approver_count"));
    }

    private record SiloRow(long id, String status) {
    }

    private record FumigationRow(long id, long siloId, Instant windowStart, Instant windowEnd,
                                 BigDecimal safeThreshold, FumigationStatus status, int version,
                                 Long fanId, String priorSiloStatus, Integer frozenPlanVersion,
                                 int approverCount) {
    }

    private record ReadingPoint(int seqNo, BigDecimal concentration, LocalDateTime measuredAt) {
    }

    private record IdemRow(String requestId, String fingerprint, String responseBody) {
    }
}
