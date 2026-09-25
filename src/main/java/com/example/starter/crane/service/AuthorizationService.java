package com.example.starter.crane.service;

import com.example.starter.crane.ConflictException;
import com.example.starter.crane.NotFoundException;
import com.example.starter.crane.api.AuthorizationRequest;
import com.example.starter.crane.api.AuthorizationView;
import com.example.starter.crane.api.ReleaseRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 重叠区授权服务。
 *
 * <p>关键口径：</p>
 * <ul>
 *   <li>同一重叠区同一时段采用半开区间 [start, end) 判定，端点相接（一方 end = 另一方 start）不冲突；</li>
 *   <li>requestId 幂等：SHA-256 指纹覆盖 requestId 与全部业务字段，同键同参回放首次结论，同键异参返回 409 且不落库不占区；</li>
 *   <li>吊重超过塔机额定载荷直接拒绝，拒绝不占用区域；</li>
 *   <li>并发授权：先对 overlap_zone 行加 FOR UPDATE 行锁串行化同区申请，再做区间冲突检测，保证同区同时段唯一授权；</li>
 *   <li>释放只改状态为 RELEASED，历史授权行保留，供位姿判罚追溯合法作业（含已结束作业）。</li>
 * </ul>
 */
@Service
public class AuthorizationService {

    static final String RESULT_GRANTED = "GRANTED";
    static final String RESULT_REJECTED_OVERLOAD = "REJECTED_OVERLOAD";
    static final String STATUS_GRANTED = "GRANTED";
    static final String STATUS_RELEASED = "RELEASED";
    static final String STATUS_REJECTED = "REJECTED";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuthorizationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 提交授权申请；同键同参返回首次结果（replayed=true 表示幂等回放）。
     */
    @Transactional
    public AuthorizationView apply(AuthorizationRequest request) {
        validateRequest(request);
        String fingerprint = fingerprint(request);

        AuthRow existing = findByRequestId(request.requestId());
        if (existing != null) {
            return replayOrConflict(existing, fingerprint);
        }

        Instant now = clock.instant();

        // 超载判定优先：超额定载荷直接拒绝并落库（结论同样受 requestId 幂等保护），不占用区域。
        BigDecimal ratedLoad = findRatedLoad(request.craneCode());
        if (request.loadKg().compareTo(ratedLoad) > 0) {
            AuthRow rejected = AuthRow.rejected(request, fingerprint, ratedLoad, now);
            insertRequestRow(rejected);
            return toView(rejected, false);
        }

        // 对目标重叠区行加锁，串行化同一重叠区的并发申请；同时校验区域存在。
        Integer zoneLocked = jdbc.query("SELECT id FROM overlap_zone WHERE zone_code = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt("id") : null, request.zoneCode());
        if (zoneLocked == null) {
            throw new NotFoundException("重叠区不存在: " + request.zoneCode());
        }

        // 加锁后二次确认幂等键（与并发同键申请决出唯一结论）。
        existing = findByRequestId(request.requestId());
        if (existing != null) {
            return replayOrConflict(existing, fingerprint);
        }

        Integer overlaps = jdbc.queryForObject(
                "SELECT COUNT(1) FROM zone_authorization "
                        + "WHERE zone_code = ? AND result = ? AND status = ? "
                        + "AND start_time < ? AND end_time > ?",
                Integer.class, request.zoneCode(), RESULT_GRANTED, STATUS_GRANTED,
                Timestamp.from(request.endTime()), Timestamp.from(request.startTime()));
        if (overlaps != null && overlaps > 0) {
            throw new ConflictException("重叠区 " + request.zoneCode() + " 在时段 ["
                    + request.startTime() + ", " + request.endTime() + ") 已被其他塔机授权占用");
        }

        AuthRow granted = AuthRow.granted(request, fingerprint, now);
        try {
            insertRequestRow(granted);
        } catch (DataIntegrityViolationException dup) {
            // 并发同键插入落败：以先落库的结论为准。
            AuthRow winner = findByRequestId(request.requestId());
            if (winner == null) {
                throw dup;
            }
            return replayOrConflict(winner, fingerprint);
        }
        return toView(granted, false);
    }

    /** 查询申请结论；不存在返回 404。 */
    public AuthorizationView get(String requestId) {
        AuthRow row = findByRequestId(requestId);
        if (row == null) {
            throw new NotFoundException("授权申请不存在: " + requestId);
        }
        return toView(row, false);
    }

    /** 释放授权（作业完成或取消）；已释放再次释放按幂等返回当前结论。 */
    @Transactional
    public AuthorizationView release(String requestId, ReleaseRequest request) {
        if (request == null || (!"COMPLETED".equals(request.releaseType())
                && !"CANCELLED".equals(request.releaseType()))) {
            throw new IllegalArgumentException("releaseType 必须为 COMPLETED 或 CANCELLED");
        }
        AuthRow row = findByRequestId(requestId);
        if (row == null) {
            throw new NotFoundException("授权申请不存在: " + requestId);
        }
        if (RESULT_REJECTED_OVERLOAD.equals(row.result())) {
            throw new ConflictException("申请已被超载拒绝，无授权可释放: " + requestId);
        }
        Instant now = clock.instant();
        int updated = jdbc.update(
                "UPDATE zone_authorization SET status = ?, released_at = ?, release_type = ?, updated_at = ? "
                        + "WHERE request_id = ? AND status = ?",
                STATUS_RELEASED, Timestamp.from(now), request.releaseType(), Timestamp.from(now),
                requestId, STATUS_GRANTED);
        if (updated == 0) {
            // 并发释放或状态已变更：以库中当前状态为唯一结论回放。
            AuthRow current = findByRequestId(requestId);
            return toView(current, false);
        }
        return toView(findByRequestId(requestId), false);
    }

    // ---- 内部方法 ----

    private void validateRequest(AuthorizationRequest request) {
        if (!request.startTime().isBefore(request.endTime())) {
            throw new IllegalArgumentException("作业起止时间必须满足 startTime < endTime（半开区间 [start, end)）");
        }
    }

    private AuthorizationView replayOrConflict(AuthRow existing, String incomingFingerprint) {
        if (!existing.fingerprint().equals(incomingFingerprint)) {
            throw new ConflictException("requestId " + existing.requestId()
                    + " 已存在但业务参数不一致（同键异参）");
        }
        return toView(existing, true);
    }

    private AuthRow findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM zone_authorization WHERE request_id = ?",
                rs -> rs.next() ? mapRow(rs) : null, requestId);
    }

    private BigDecimal findRatedLoad(String craneCode) {
        return jdbc.query("SELECT rated_load_kg FROM tower_crane WHERE crane_code = ?",
                rs -> {
                    if (!rs.next()) {
                        throw new NotFoundException("塔机不存在: " + craneCode);
                    }
                    return rs.getBigDecimal("rated_load_kg");
                }, craneCode);
    }

    private void insertRequestRow(AuthRow row) {
        jdbc.update("INSERT INTO zone_authorization (request_id, crane_code, zone_code, start_time, end_time, "
                        + "load_kg, fingerprint, result, status, reject_reason, granted_at, released_at, "
                        + "release_type, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.requestId(), row.craneCode(), row.zoneCode(),
                Timestamp.from(row.startTime()), Timestamp.from(row.endTime()),
                row.loadKg(), row.fingerprint(), row.result(), row.status(), row.rejectReason(),
                row.grantedAt() == null ? null : Timestamp.from(row.grantedAt()),
                row.releasedAt() == null ? null : Timestamp.from(row.releasedAt()),
                row.releaseType(), Timestamp.from(row.createdAt()), Timestamp.from(row.updatedAt()));
    }

    private AuthRow mapRow(ResultSet rs) throws SQLException {
        return new AuthRow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("crane_code"),
                rs.getString("zone_code"),
                rs.getTimestamp("start_time").toInstant(),
                rs.getTimestamp("end_time").toInstant(),
                rs.getBigDecimal("load_kg"),
                rs.getString("fingerprint"),
                rs.getString("result"),
                rs.getString("status"),
                rs.getString("reject_reason"),
                rs.getTimestamp("granted_at") == null ? null : rs.getTimestamp("granted_at").toInstant(),
                rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant(),
                rs.getString("release_type"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private AuthorizationView toView(AuthRow row, boolean replayed) {
        return new AuthorizationView(row.requestId(), row.result(), row.status(),
                row.craneCode(), row.zoneCode(), row.startTime(), row.endTime(),
                row.loadKg(), row.rejectReason(), replayed);
    }

    /**
     * 计算申请指纹：覆盖 requestId 与全部业务字段，数值按去尾零规范串、时间按 UTC 毫秒。
     */
    static String fingerprint(AuthorizationRequest r) {
        String canonical = String.join("|",
                r.requestId(),
                r.craneCode(),
                r.zoneCode(),
                Long.toString(r.startTime().toEpochMilli()),
                Long.toString(r.endTime().toEpochMilli()),
                r.loadKg().stripTrailingZeros().toPlainString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** zone_authorization 行对象。 */
    private record AuthRow(
            Long id,
            String requestId,
            String craneCode,
            String zoneCode,
            Instant startTime,
            Instant endTime,
            BigDecimal loadKg,
            String fingerprint,
            String result,
            String status,
            String rejectReason,
            Instant grantedAt,
            Instant releasedAt,
            String releaseType,
            Instant createdAt,
            Instant updatedAt) {

        static AuthRow granted(AuthorizationRequest r, String fingerprint, Instant now) {
            return new AuthRow(null, r.requestId(), r.craneCode(), r.zoneCode(),
                    r.startTime(), r.endTime(), r.loadKg(), fingerprint,
                    RESULT_GRANTED, STATUS_GRANTED, null, now, null, null, now, now);
        }

        static AuthRow rejected(AuthorizationRequest r, String fingerprint,
                BigDecimal ratedLoad, Instant now) {
            String reason = "吊重 " + r.loadKg().stripTrailingZeros().toPlainString()
                    + "kg 超过额定载荷 " + ratedLoad.stripTrailingZeros().toPlainString() + "kg";
            return new AuthRow(null, r.requestId(), r.craneCode(), r.zoneCode(),
                    r.startTime(), r.endTime(), r.loadKg(), fingerprint,
                    RESULT_REJECTED_OVERLOAD, STATUS_REJECTED, reason, null, null, null, now, now);
        }
    }
}
