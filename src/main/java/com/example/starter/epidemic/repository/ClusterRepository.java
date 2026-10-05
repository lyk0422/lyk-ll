package com.example.starter.epidemic.repository;

import com.example.starter.epidemic.domain.ClusterEventPo;
import com.example.starter.epidemic.domain.ClusterStatus;
import com.example.starter.epidemic.domain.Conclusion;
import com.example.starter.epidemic.domain.InvestigationPo;
import com.example.starter.epidemic.domain.InvestigationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 聚集性分析数据访问：暴露场所归并、聚集事件、迟到记录、协查单。
 *
 * <p>并发保证：{@code cluster_event.open_key} 与 {@code investigation.active_event_key}
 * 唯一约束分别保证“同场所同时刻一个未关闭事件”“同事件同时刻一张进行中协查单”；
 * 事件行 {@code FOR UPDATE} 串行化归并与状态推进。</p>
 */
@Repository
public class ClusterRepository {

    private final JdbcTemplate jdbc;

    public ClusterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 聚集事件 ----------------

    private static final RowMapper<ClusterEventPo> EVENT_MAPPER = (rs, n) -> {
        ClusterEventPo po = new ClusterEventPo();
        po.setId(rs.getLong("id"));
        po.setEventNo(rs.getString("event_no"));
        po.setSiteCode(rs.getString("site_code"));
        po.setStatus(ClusterStatus.valueOf(rs.getString("status")));
        po.setFirstCaseAt(rs.getTimestamp("first_case_at").toInstant());
        po.setLastCaseAt(rs.getTimestamp("last_case_at").toInstant());
        po.setCardCount(rs.getInt("card_count"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        Timestamp closedAt = rs.getTimestamp("closed_at");
        po.setClosedAt(closedAt == null ? null : closedAt.toInstant());
        return po;
    };

    private static final String EVENT_SELECT = """
            SELECT id, event_no, site_code, status, first_case_at, last_case_at, card_count,
                   created_at, closed_at
            FROM cluster_event
            """;

    public long insertEvent(ClusterEventPo po) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO cluster_event (event_no, site_code, status, first_case_at,
                        last_case_at, card_count, open_key, created_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setString(1, po.getEventNo());
            ps.setString(2, po.getSiteCode());
            ps.setString(3, po.getStatus().name());
            ps.setTimestamp(4, Timestamp.from(po.getFirstCaseAt()));
            ps.setTimestamp(5, Timestamp.from(po.getLastCaseAt()));
            ps.setInt(6, po.getCardCount());
            ps.setString(7, po.getSiteCode());
            ps.setTimestamp(8, Timestamp.from(po.getCreatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("聚集事件写回失败");
        }
        return key.longValue();
    }

    public Optional<ClusterEventPo> findEventByNo(String eventNo) {
        return jdbc.query(EVENT_SELECT + " WHERE event_no = ?", EVENT_MAPPER, eventNo)
                .stream().findFirst();
    }

    /** 主键行级加锁（事务内调用），串行化事件推进/结案回写/关闭。 */
    public Optional<ClusterEventPo> findEventByNoForUpdate(String eventNo) {
        return jdbc.query(EVENT_SELECT + " WHERE event_no = ? FOR UPDATE", EVENT_MAPPER, eventNo)
                .stream().findFirst();
    }

    /** 查找场所当前未关闭事件（open_key 占位），事务内加锁。 */
    public Optional<ClusterEventPo> findOpenEventForUpdate(String siteCode) {
        return jdbc.query(EVENT_SELECT + " WHERE open_key = ? FOR UPDATE", EVENT_MAPPER, siteCode)
                .stream().findFirst();
    }

    /** 场所最近一个已关闭事件编号（用于迟到记录），无则空。 */
    public Optional<String> findLatestClosedEventNo(String siteCode) {
        return jdbc.query("""
                SELECT event_no FROM cluster_event
                WHERE site_code = ? AND status = 'CLOSED' ORDER BY id DESC LIMIT 1
                """, (rs, n) -> rs.getString("event_no"), siteCode).stream().findFirst();
    }

    /** 新卡归入未关闭事件：推进末例时刻与卡片数。 */
    public int attachCardToEvent(long eventId, Instant lastCaseAt, int cardCount) {
        return jdbc.update("""
                UPDATE cluster_event SET last_case_at = ?, card_count = ?
                WHERE id = ? AND status <> 'CLOSED'
                """, Timestamp.from(lastCaseAt), cardCount, eventId);
    }

    /** 协查结论回写事件状态（与协查结案同事务）；仅未关闭事件可推进。 */
    public int updateEventStatus(long eventId, ClusterStatus newStatus) {
        return jdbc.update("""
                UPDATE cluster_event SET status = ?
                WHERE id = ? AND status <> 'CLOSED'
                """, newStatus.name(), eventId);
    }

    /** 关闭事件（终态）：释放 open_key 占位，返回影响行数（0 表示已关闭）。 */
    public int closeEvent(long eventId, Instant closedAt) {
        return jdbc.update("""
                UPDATE cluster_event SET status = 'CLOSED', closed_at = ?, open_key = NULL
                WHERE id = ? AND status <> 'CLOSED'
                """, Timestamp.from(closedAt), eventId);
    }

    // ---------------- 事件涉及卡片 ----------------

    public void insertEventCard(long eventId, String cardNo, Instant attachedAt) {
        jdbc.update("""
                INSERT INTO cluster_event_card (event_id, card_no, attached_at)
                VALUES (?,?,?)
                """, eventId, cardNo, Timestamp.from(attachedAt));
    }

    /** 事件涉及卡号（按归入顺序，稳定可复算）。 */
    public List<String> findEventCardNos(long eventId) {
        return jdbc.query("""
                SELECT card_no FROM cluster_event_card
                WHERE event_id = ? ORDER BY attached_at, card_no
                """, (rs, n) -> rs.getString("card_no"), eventId);
    }

    /** 事件涉及卡片的归入明细（时间线复算用）。 */
    public List<CardAttachment> findEventCards(long eventId) {
        return jdbc.query("""
                SELECT card_no, attached_at FROM cluster_event_card
                WHERE event_id = ? ORDER BY attached_at, card_no
                """, (rs, n) -> new CardAttachment(rs.getString("card_no"),
                rs.getTimestamp("attached_at").toInstant()), eventId);
    }

    /** 卡片归入明细。 */
    public record CardAttachment(String cardNo, Instant attachedAt) {
    }

    /**
     * 场所 72 小时窗口内、尚未归入任何事件的有效报告卡（撤回卡不计），按报告时刻升序。
     */
    public List<WindowCard> findWindowCards(String siteCode, Instant since) {
        return jdbc.query("""
                SELECT c.card_no, c.created_at, c.institution_code FROM card c
                WHERE c.exposure_site_code = ? AND c.created_at >= ?
                  AND c.status <> 'WITHDRAWN'
                  AND NOT EXISTS (SELECT 1 FROM cluster_event_card cc WHERE cc.card_no = c.card_no)
                ORDER BY c.created_at, c.card_no
                """, (rs, n) -> new WindowCard(rs.getString("card_no"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("institution_code")), siteCode, Timestamp.from(since));
    }

    /** 窗口内候选卡片。 */
    public record WindowCard(String cardNo, Instant createdAt, String institutionCode) {
    }

    // ---------------- 迟到记录 ----------------

    public void insertLateRecord(String siteCode, String cardNo, String closedEventNo,
                                 Instant recordedAt) {
        jdbc.update("""
                INSERT INTO cluster_late_record (site_code, card_no, closed_event_no, recorded_at)
                VALUES (?,?,?,?)
                """, siteCode, cardNo, closedEventNo, Timestamp.from(recordedAt));
    }

    // ---------------- 协查单 ----------------

    private static final RowMapper<InvestigationPo> INVESTIGATION_MAPPER = (rs, n) -> {
        InvestigationPo po = new InvestigationPo();
        po.setId(rs.getLong("id"));
        po.setEventNo(rs.getString("event_no"));
        po.setRequestId(rs.getString("request_id"));
        po.setStatus(InvestigationStatus.valueOf(rs.getString("status")));
        String conclusion = rs.getString("conclusion");
        po.setConclusion(conclusion == null ? null : Conclusion.valueOf(conclusion));
        po.setNote(rs.getString("note"));
        po.setCreator(rs.getString("creator"));
        po.setOperator(rs.getString("operator"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        Timestamp concludedAt = rs.getTimestamp("concluded_at");
        po.setConcludedAt(concludedAt == null ? null : concludedAt.toInstant());
        return po;
    };

    private static final String INVESTIGATION_SELECT = """
            SELECT id, event_no, request_id, status, conclusion, note, creator, operator,
                   created_at, concluded_at
            FROM investigation
            """;

    public long insertInvestigation(InvestigationPo po) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO investigation (event_no, request_id, status, note, creator,
                        active_event_key, created_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setString(1, po.getEventNo());
            ps.setString(2, po.getRequestId());
            ps.setString(3, po.getStatus().name());
            ps.setString(4, po.getNote());
            ps.setString(5, po.getCreator());
            ps.setString(6, po.getEventNo());
            ps.setTimestamp(7, Timestamp.from(po.getCreatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("协查单写回失败");
        }
        return key.longValue();
    }

    public Optional<InvestigationPo> findInvestigation(long id) {
        return jdbc.query(INVESTIGATION_SELECT + " WHERE id = ?", INVESTIGATION_MAPPER, id)
                .stream().findFirst();
    }

    /** 协查单行级加锁（事务内调用），串行化结案。 */
    public Optional<InvestigationPo> findInvestigationForUpdate(long id) {
        return jdbc.query(INVESTIGATION_SELECT + " WHERE id = ? FOR UPDATE",
                INVESTIGATION_MAPPER, id).stream().findFirst();
    }

    /** 事件当前进行中协查单（active_event_key 占位）。 */
    public Optional<InvestigationPo> findOpenInvestigation(String eventNo) {
        return jdbc.query(INVESTIGATION_SELECT + " WHERE active_event_key = ?",
                INVESTIGATION_MAPPER, eventNo).stream().findFirst();
    }

    /** 事件全部协查单（协查轨迹，按生成顺序）。 */
    public List<InvestigationPo> findInvestigationsByEvent(String eventNo) {
        return jdbc.query(INVESTIGATION_SELECT + " WHERE event_no = ? ORDER BY id",
                INVESTIGATION_MAPPER, eventNo);
    }

    /** 结案：仅进行中协查单可结案，释放 active_event_key 占位，返回影响行数。 */
    public int concludeInvestigation(long id, Conclusion conclusion, String operator,
                                     String note, Instant concludedAt) {
        return jdbc.update("""
                UPDATE investigation
                SET status = 'CONCLUDED', conclusion = ?, operator = ?,
                    note = COALESCE(?, note), concluded_at = ?, active_event_key = NULL
                WHERE id = ? AND status = 'OPEN'
                """, conclusion.name(), operator, note, Timestamp.from(concludedAt), id);
    }

    // ---------------- 协查推送机构 ----------------

    public void insertTarget(long investigationId, String institutionCode) {
        jdbc.update("""
                INSERT INTO investigation_target (investigation_id, institution_code)
                VALUES (?,?)
                """, investigationId, institutionCode);
    }

    public List<String> findTargets(long investigationId) {
        return jdbc.query("""
                SELECT institution_code FROM investigation_target
                WHERE investigation_id = ? ORDER BY institution_code
                """, (rs, n) -> rs.getString("institution_code"), investigationId);
    }
}
