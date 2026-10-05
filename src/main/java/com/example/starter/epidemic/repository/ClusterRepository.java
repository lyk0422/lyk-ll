package com.example.starter.epidemic.repository;

import com.example.starter.epidemic.domain.ArrivalType;
import com.example.starter.epidemic.domain.ClusterEventCardPo;
import com.example.starter.epidemic.domain.ClusterEventPo;
import com.example.starter.epidemic.domain.ClusterStatus;
import com.example.starter.epidemic.domain.ExposureSitePo;
import com.example.starter.epidemic.domain.InvestigationPo;
import com.example.starter.epidemic.domain.InvestigationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 聚集性分析数据访问：暴露场所库、卡-场所归并、聚集事件、事件卡号与协查单。
 *
 * <p>同一场所的归并通过对场所行 {@code FOR UPDATE} 加锁串行化；同一事件同一时刻
 * 仅一张进行中协查单由 {@code open_investigation_id} 的条件 UPDATE 裁决。</p>
 */
@Repository
public class ClusterRepository {

    private final JdbcTemplate jdbc;

    public ClusterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 暴露场所库 ----------------

    private static final RowMapper<ExposureSitePo> SITE_MAPPER = (rs, n) ->
            new ExposureSitePo(rs.getString("code"), rs.getString("name"), rs.getString("keyword"));

    public List<ExposureSitePo> findAllSites() {
        return jdbc.query("SELECT code, name, keyword FROM exposure_site ORDER BY code", SITE_MAPPER);
    }

    /** 对场所行加锁（事务内调用），串行化同场所的聚集归并。 */
    public void lockSite(String siteCode) {
        jdbc.query("SELECT code FROM exposure_site WHERE code = ? FOR UPDATE",
                (rs, n) -> rs.getString("code"), siteCode);
    }

    // ---------------- 卡-场所归并 ----------------

    public void insertCardExposure(String cardNo, String siteCode, Instant reportedAt) {
        jdbc.update("INSERT INTO card_exposure (card_no, site_code, reported_at) VALUES (?,?,?)",
                cardNo, siteCode, Timestamp.from(reportedAt));
    }

    /**
     * 窗口内尚未正常归并到任何事件的候选卡（迟到卡可再次参与新事件归并）。
     */
    public List<ClusterEventCardPo> findUnassignedExposures(String siteCode, Instant windowStart,
                                                            Instant windowEnd) {
        return jdbc.query("""
                SELECT e.card_no, e.reported_at FROM card_exposure e
                WHERE e.site_code = ? AND e.reported_at >= ? AND e.reported_at <= ?
                  AND e.card_no NOT IN (
                      SELECT c.card_no FROM cluster_event_card c WHERE c.arrival = 'NORMAL')
                ORDER BY e.reported_at, e.card_no
                """,
                (rs, n) -> new ClusterEventCardPo(0L, 0L, rs.getString("card_no"),
                        rs.getTimestamp("reported_at").toInstant(), ArrivalType.NORMAL),
                siteCode, Timestamp.from(windowStart), Timestamp.from(windowEnd));
    }

    // ---------------- 聚集事件 ----------------

    private static final RowMapper<ClusterEventPo> EVENT_MAPPER = (rs, n) -> {
        ClusterEventPo po = new ClusterEventPo();
        po.setId(rs.getLong("id"));
        po.setSiteCode(rs.getString("site_code"));
        po.setStatus(ClusterStatus.valueOf(rs.getString("status")));
        po.setFirstCaseAt(rs.getTimestamp("first_case_at").toInstant());
        po.setLastCaseAt(rs.getTimestamp("last_case_at").toInstant());
        long openInv = rs.getLong("open_investigation_id");
        po.setOpenInvestigationId(rs.wasNull() ? null : openInv);
        po.setConclusion(rs.getString("conclusion"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        Timestamp closedAt = rs.getTimestamp("closed_at");
        po.setClosedAt(closedAt == null ? null : closedAt.toInstant());
        return po;
    };

    private static final String EVENT_SELECT = """
            SELECT id, site_code, status, first_case_at, last_case_at, open_investigation_id,
                   conclusion, created_at, closed_at
            FROM cluster_event
            """;

    public long insertEvent(ClusterEventPo po) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO cluster_event (site_code, status, first_case_at, last_case_at, created_at)
                    VALUES (?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setString(1, po.getSiteCode());
            ps.setString(2, po.getStatus().name());
            ps.setTimestamp(3, Timestamp.from(po.getFirstCaseAt()));
            ps.setTimestamp(4, Timestamp.from(po.getLastCaseAt()));
            ps.setTimestamp(5, Timestamp.from(po.getCreatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("聚集事件写回失败");
        }
        return key.longValue();
    }

    public Optional<ClusterEventPo> findEvent(long id) {
        return jdbc.query(EVENT_SELECT + " WHERE id = ?", EVENT_MAPPER, id).stream().findFirst();
    }

    /** 主键行级加锁（事务内调用）。 */
    public Optional<ClusterEventPo> findEventForUpdate(long id) {
        return jdbc.query(EVENT_SELECT + " WHERE id = ? FOR UPDATE", EVENT_MAPPER, id)
                .stream().findFirst();
    }

    /** 场所内最新一个未关闭（可继续归并）的事件。 */
    public Optional<ClusterEventPo> findActiveEvent(String siteCode) {
        return jdbc.query(EVENT_SELECT + """
                 WHERE site_code = ? AND status <> 'CLOSED'
                 ORDER BY id DESC LIMIT 1
                """, EVENT_MAPPER, siteCode).stream().findFirst();
    }

    /** 场所内最新一个已关闭事件（用于登记迟到卡）。 */
    public Optional<ClusterEventPo> findLatestClosedEvent(String siteCode) {
        return jdbc.query(EVENT_SELECT + """
                 WHERE site_code = ? AND status = 'CLOSED'
                 ORDER BY id DESC LIMIT 1
                """, EVENT_MAPPER, siteCode).stream().findFirst();
    }

    public List<ClusterEventPo> findAllEvents() {
        return jdbc.query(EVENT_SELECT + " ORDER BY id", EVENT_MAPPER);
    }

    /** 正常归并新卡后推进末例时刻（首例时刻不变）。 */
    public void updateEventLastCase(long eventId, Instant lastCaseAt) {
        jdbc.update("UPDATE cluster_event SET last_case_at = ? WHERE id = ?",
                Timestamp.from(lastCaseAt), eventId);
    }

    /**
     * 占用事件的进行中协查单位置：仅当当前无进行中协查单时生效，
     * 返回影响行数（0 表示已存在进行中协查单，并发生成唯一成功）。
     */
    public int occupyOpenInvestigation(long eventId, long investigationId) {
        return jdbc.update("""
                UPDATE cluster_event SET open_investigation_id = ?
                WHERE id = ? AND open_investigation_id IS NULL AND status <> 'CLOSED'
                """, investigationId, eventId);
    }

    /** 协查结论回写事件状态并释放进行中协查单位置（与协查单办结同事务）。 */
    public int writeBackConclusion(long eventId, long investigationId, ClusterStatus result,
                                   String conclusion) {
        return jdbc.update("""
                UPDATE cluster_event
                SET status = ?, conclusion = ?, open_investigation_id = NULL
                WHERE id = ? AND open_investigation_id = ? AND status <> 'CLOSED'
                """, result.name(), conclusion, eventId, investigationId);
    }

    /** 关闭事件（终态）：仅当未关闭时生效，返回影响行数。 */
    public int closeEvent(long eventId, Instant closedAt) {
        return jdbc.update("""
                UPDATE cluster_event SET status = 'CLOSED', closed_at = ?
                WHERE id = ? AND status <> 'CLOSED'
                """, Timestamp.from(closedAt), eventId);
    }

    // ---------------- 事件卡号 ----------------

    private static final RowMapper<ClusterEventCardPo> EVENT_CARD_MAPPER = (rs, n) ->
            new ClusterEventCardPo(rs.getLong("id"), rs.getLong("event_id"),
                    rs.getString("card_no"), rs.getTimestamp("reported_at").toInstant(),
                    ArrivalType.valueOf(rs.getString("arrival")));

    public void insertEventCard(long eventId, String cardNo, Instant reportedAt, ArrivalType arrival) {
        jdbc.update("""
                INSERT INTO cluster_event_card (event_id, card_no, reported_at, arrival)
                VALUES (?,?,?,?)
                """, eventId, cardNo, Timestamp.from(reportedAt), arrival.name());
    }

    /** 事件卡号时间线：按报告时刻与主键稳定排序，重复查询结果一致。 */
    public List<ClusterEventCardPo> findEventCards(long eventId) {
        return jdbc.query("""
                SELECT id, event_id, card_no, reported_at, arrival
                FROM cluster_event_card WHERE event_id = ? ORDER BY reported_at, id
                """, EVENT_CARD_MAPPER, eventId);
    }

    public int countEventCards(long eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cluster_event_card WHERE event_id = ?",
                Integer.class, eventId);
        return count == null ? 0 : count;
    }

    // ---------------- 协查单 ----------------

    private static final RowMapper<InvestigationPo> INVESTIGATION_MAPPER = (rs, n) -> {
        InvestigationPo po = new InvestigationPo();
        po.setId(rs.getLong("id"));
        po.setEventId(rs.getLong("event_id"));
        po.setRequestId(rs.getString("request_id"));
        po.setInstitutionCode(rs.getString("institution_code"));
        po.setNote(rs.getString("note"));
        po.setStatus(InvestigationStatus.valueOf(rs.getString("status")));
        po.setConclusion(rs.getString("conclusion"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        Timestamp concludedAt = rs.getTimestamp("concluded_at");
        po.setConcludedAt(concludedAt == null ? null : concludedAt.toInstant());
        return po;
    };

    private static final String INVESTIGATION_SELECT = """
            SELECT id, event_id, request_id, institution_code, note, status, conclusion,
                   created_at, concluded_at
            FROM investigation_order
            """;

    public long insertInvestigation(InvestigationPo po) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO investigation_order
                        (event_id, request_id, institution_code, note, status, created_at)
                    VALUES (?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setLong(1, po.getEventId());
            ps.setString(2, po.getRequestId());
            ps.setString(3, po.getInstitutionCode());
            if (po.getNote() == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, po.getNote());
            }
            ps.setString(5, po.getStatus().name());
            ps.setTimestamp(6, Timestamp.from(po.getCreatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("协查单写回失败");
        }
        return key.longValue();
    }

    public Optional<InvestigationPo> findInvestigationForUpdate(long id) {
        return jdbc.query(INVESTIGATION_SELECT + " WHERE id = ? FOR UPDATE",
                INVESTIGATION_MAPPER, id).stream().findFirst();
    }

    /** 办结协查单：仅当进行中时生效，返回影响行数。 */
    public int concludeInvestigation(long id, String conclusion, Instant concludedAt) {
        return jdbc.update("""
                UPDATE investigation_order
                SET status = 'CONCLUDED', conclusion = ?, concluded_at = ?
                WHERE id = ? AND status = 'IN_PROGRESS'
                """, conclusion, Timestamp.from(concludedAt), id);
    }

    /** 事件协查轨迹：按主键稳定排序，重复查询结果一致。 */
    public List<InvestigationPo> findInvestigationsByEvent(long eventId) {
        return jdbc.query(INVESTIGATION_SELECT + " WHERE event_id = ? ORDER BY id",
                INVESTIGATION_MAPPER, eventId);
    }
}
