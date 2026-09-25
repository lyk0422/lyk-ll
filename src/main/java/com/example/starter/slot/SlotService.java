package com.example.starter.slot;

import com.example.starter.slot.dto.PublicationView;
import com.example.starter.slot.dto.ReturnResultView;
import com.example.starter.slot.dto.SlotAllocationView;
import com.example.starter.slot.dto.SlotConflictView;
import com.example.starter.slot.dto.SlotResultView;
import com.example.starter.slot.dto.SlotSeriesRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 航班时刻协调核心服务：系列申请（含历史优先权裁决）、退回释放、快照发布。
 *
 * <p>关键不变量：</p>
 * <ul>
 *   <li>同一时刻同一周只能分配给一家航司，由 slot_allocation 唯一约束兜底；</li>
 *   <li>一个系列的多周分配在同一事务内整体成功或整体回滚；</li>
 *   <li>requestId 幂等：同键同参返回首次结果，同键异参返回 409；</li>
 *   <li>分配表发布后本期冻结，迟到退回不改变已发布快照。</li>
 * </ul>
 */
@Service
public class SlotService {

    private static final String STATUS_ALLOCATED = "ALLOCATED";
    private static final String STATUS_RETURNED = "RETURNED";
    private static final String TYPE_RETAINED = "RETAINED";
    private static final String TYPE_POOL = "POOL";

    private final JdbcTemplate jdbc;

    public SlotService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 提交系列申请。历史执行率达到 80% 的系列自动保留占用，否则进入公共池竞争。
     */
    @Transactional
    public SlotResultView apply(SlotSeriesRequest request) {
        String slotTime = normalizeSlotTime(request.slotTime());
        List<Integer> weeks = normalizeWeeks(request.weeks());
        String weeksCsv = weeks.stream().map(String::valueOf).collect(Collectors.joining(","));
        String fingerprint = fingerprint(request.requestId(), request.flightNo(), slotTime, weeksCsv);

        Map<String, Object> existing = findRequest(request.requestId());
        if (existing != null) {
            return replayOrMismatch(existing, fingerprint);
        }

        assertNotFrozen();

        String allocType = resolveAllocType(request.airline(), request.flightNo(), slotTime);

        try {
            jdbc.update("INSERT INTO slot_request(request_id, airline, flight_no, slot_time, weeks_csv,"
                            + " fingerprint, status) VALUES (?,?,?,?,?,?,?)",
                    request.requestId(), request.airline(), request.flightNo(), slotTime, weeksCsv,
                    fingerprint, STATUS_ALLOCATED);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 已落库，按幂等规则处理
            return replayOrMismatch(findRequest(request.requestId()), fingerprint);
        }

        // 先查后插：冲突清单完整；并发竞争由唯一约束兜底，冲突即整体回滚
        List<SlotConflictView> conflicts = findConflicts(slotTime, weeks);
        if (!conflicts.isEmpty()) {
            throw new SlotConflictException("SLOT_CONFLICT", "申请时刻已被其他航司占用", conflicts);
        }
        try {
            for (int week : weeks) {
                jdbc.update("INSERT INTO slot_allocation(request_id, airline, flight_no, slot_time,"
                                + " week_no, alloc_type) VALUES (?,?,?,?,?,?)",
                        request.requestId(), request.airline(), request.flightNo(), slotTime, week, allocType);
            }
        } catch (DuplicateKeyException e) {
            throw new SlotConflictException("SLOT_CONFLICT", "申请时刻与其他航司并发竞争失败",
                    findConflicts(slotTime, weeks));
        }
        return buildView(request.requestId());
    }

    /**
     * 退回已分配系列，释放其全部时刻；释放后立即可被他人申请。重复退回幂等返回空清单。
     * 发布后迟到退回仅释放占用表，不改变已发布快照。
     */
    @Transactional
    public ReturnResultView release(String requestId) {
        Map<String, Object> existing = findRequest(requestId);
        if (existing == null) {
            throw new SlotNotFoundException("系列不存在: " + requestId);
        }
        if (STATUS_RETURNED.equals(existing.get("status"))) {
            return new ReturnResultView(requestId, List.of());
        }
        List<SlotAllocationView> released = listAllocations(requestId);
        jdbc.update("DELETE FROM slot_allocation WHERE request_id=?", requestId);
        jdbc.update("UPDATE slot_request SET status=? WHERE request_id=?", STATUS_RETURNED, requestId);
        return new ReturnResultView(requestId, released);
    }

    /**
     * 查询系列当前状态。
     */
    @Transactional(readOnly = true)
    public SlotResultView getRequest(String requestId) {
        if (findRequest(requestId) == null) {
            throw new SlotNotFoundException("系列不存在: " + requestId);
        }
        return buildView(requestId);
    }

    /**
     * 发布本期分配表快照；发布后本期冻结，重复发布幂等成功。
     */
    @Transactional
    public PublicationView publish() {
        ensurePublicationRow();
        boolean alreadyPublished = isPublishedForUpdate();
        if (!alreadyPublished) {
            jdbc.update("INSERT INTO slot_snapshot(slot_time, week_no, airline, flight_no, alloc_type)"
                    + " SELECT slot_time, week_no, airline, flight_no, alloc_type FROM slot_allocation");
            jdbc.update("UPDATE slot_publication SET published_at=CURRENT_TIMESTAMP WHERE id=1");
        }
        List<PublicationView.SnapshotEntry> snapshot = jdbc.query(
                "SELECT slot_time, week_no, airline, flight_no, alloc_type FROM slot_snapshot"
                        + " ORDER BY slot_time, week_no",
                (rs, i) -> new PublicationView.SnapshotEntry(rs.getString(1), rs.getInt(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)));
        return new PublicationView(snapshot.size(), alreadyPublished, snapshot);
    }

    /**
     * 幂等重放：指纹一致返回首次结果，不一致抛 409。
     */
    private SlotResultView replayOrMismatch(Map<String, Object> existing, String fingerprint) {
        if (!fingerprint.equals(existing.get("fingerprint"))) {
            throw new SlotConflictException("IDEMPOTENT_MISMATCH",
                    "requestId 已存在但申请参数不一致", List.of());
        }
        return buildView((String) existing.get("request_id"));
    }

    /**
     * 历史优先权裁决：上一航季同系列执行率 = executed/planned，达到 80% 即保留原时刻。
     * 为避免浮点误差，按 executed*5 >= planned*4 判定（恰好 80% 视为达标）。
     */
    private String resolveAllocType(String airline, String flightNo, String slotTime) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT executed, planned FROM slot_history"
                        + " WHERE airline=? AND flight_no=? AND slot_time=?",
                airline, flightNo, slotTime);
        if (rows.isEmpty()) {
            return TYPE_POOL;
        }
        long executed = ((Number) rows.get(0).get("executed")).longValue();
        long planned = ((Number) rows.get(0).get("planned")).longValue();
        return planned > 0 && executed * 5 >= planned * 4 ? TYPE_RETAINED : TYPE_POOL;
    }

    /**
     * 发布冻结检查：对发布控制行加写锁，保证与 publish 互斥，申请不会越过发布点。
     */
    private void assertNotFrozen() {
        ensurePublicationRow();
        if (isPublishedForUpdate()) {
            throw new PeriodFrozenException("本期分配表已发布，时刻申请通道已冻结");
        }
    }

    private boolean isPublishedForUpdate() {
        return Boolean.TRUE.equals(jdbc.query(
                "SELECT published_at FROM slot_publication WHERE id=1 FOR UPDATE",
                rs -> rs.next() && rs.getTimestamp(1) != null));
    }

    private void ensurePublicationRow() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM slot_publication WHERE id=1", Integer.class);
        if (count == null || count == 0) {
            try {
                jdbc.update("INSERT INTO slot_publication(id, published_at) VALUES (1, NULL)");
            } catch (DuplicateKeyException ignored) {
                // 并发下已由其他事务创建
            }
        }
    }

    private List<SlotConflictView> findConflicts(String slotTime, List<Integer> weeks) {
        String placeholders = weeks.stream().map(w -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(slotTime);
        args.addAll(weeks);
        return jdbc.query("SELECT slot_time, week_no, airline FROM slot_allocation"
                        + " WHERE slot_time=? AND week_no IN (" + placeholders + ") ORDER BY week_no",
                (rs, i) -> new SlotConflictView(rs.getString(1), rs.getInt(2), rs.getString(3)),
                args.toArray());
    }

    private Map<String, Object> findRequest(String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT request_id, airline, flight_no, status, fingerprint FROM slot_request"
                        + " WHERE request_id=?", requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SlotResultView buildView(String requestId) {
        Map<String, Object> request = findRequest(requestId);
        return new SlotResultView(requestId,
                (String) request.get("airline"),
                (String) request.get("flight_no"),
                (String) request.get("status"),
                listAllocations(requestId));
    }

    private List<SlotAllocationView> listAllocations(String requestId) {
        return jdbc.query(
                "SELECT slot_time, week_no, alloc_type FROM slot_allocation"
                        + " WHERE request_id=? ORDER BY week_no",
                (rs, i) -> new SlotAllocationView(rs.getString(1), rs.getInt(2), rs.getString(3)),
                requestId);
    }

    /**
     * 时刻规范化：必须为 HH:mm 且落在 5 分钟粒度上。
     */
    private String normalizeSlotTime(String slotTime) {
        int minute = Integer.parseInt(slotTime.substring(3, 5));
        if (minute % 5 != 0) {
            throw new IllegalArgumentException("slotTime 必须为 5 分钟粒度（分钟须为 00/05/.../55）");
        }
        return slotTime;
    }

    /**
     * 覆盖周规范化：升序去重，周序号从 1 开始。
     */
    private List<Integer> normalizeWeeks(List<Integer> weeks) {
        List<Integer> normalized = weeks.stream().distinct().sorted().toList();
        if (normalized.isEmpty() || normalized.get(0) < 1) {
            throw new IllegalArgumentException("weeks 必须非空且周序号 >= 1");
        }
        return normalized;
    }

    /**
     * 幂等指纹：requestId、航班号、时刻与全部覆盖周规范化表示的 SHA-256。
     */
    private String fingerprint(String requestId, String flightNo, String slotTime, String weeksCsv) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((requestId + "|" + flightNo + "|" + slotTime + "|" + weeksCsv)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
