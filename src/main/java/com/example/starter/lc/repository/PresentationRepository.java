package com.example.starter.lc.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.lc.domain.PresentationBatch;
import com.example.starter.lc.domain.PresentationStatus;

/**
 * 交单批次数据访问。
 */
@Repository
public class PresentationRepository {

    private final JdbcTemplate jdbcTemplate;

    public PresentationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入新批次（active_slot=信用证ID，占用唯一审单中位置）。
     * 唯一约束冲突时抛出 {@link org.springframework.dao.DuplicateKeyException}。
     */
    public void insert(PresentationBatch b) {
        jdbcTemplate.update("""
                INSERT INTO presentation_batch
                  (presentation_no, lc_no, batch_seq, supersedes_id, status, draw_amount, docs_json,
                   received_at_utc, deadline_utc, notice_at_utc, accepted_at_utc, paid_at_utc,
                   refunded_at_utc, active_slot, created_at_utc, updated_at_utc)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                b.getPresentationNo(), b.getLcNo(), b.getBatchSeq(), b.getSupersedesId(),
                b.getStatus().name(), b.getDrawAmount(), b.getDocsJson(),
                Timestamp.valueOf(b.getReceivedAtUtc()), Timestamp.valueOf(b.getDeadlineUtc()),
                b.getNoticeAtUtc() == null ? null : Timestamp.valueOf(b.getNoticeAtUtc()),
                b.getAcceptedAtUtc() == null ? null : Timestamp.valueOf(b.getAcceptedAtUtc()),
                b.getPaidAtUtc() == null ? null : Timestamp.valueOf(b.getPaidAtUtc()),
                b.getRefundedAtUtc() == null ? null : Timestamp.valueOf(b.getRefundedAtUtc()),
                b.getActiveSlot(), Timestamp.valueOf(b.getCreatedAtUtc()),
                Timestamp.valueOf(b.getUpdatedAtUtc()));
    }

    /** 按业务编号查询批次，不存在返回 null。 */
    public PresentationBatch findByNo(String presentationNo) {
        List<PresentationBatch> list = jdbcTemplate.query(BASE_SELECT + " WHERE presentation_no = ?",
                ROW_MAPPER, presentationNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询信用证下仍处于审单中的批次。 */
    public List<PresentationBatch> findUnderReview(String lcNo) {
        return jdbcTemplate.query(
                BASE_SELECT + " WHERE lc_no = ? AND status = 'UNDER_REVIEW'", ROW_MAPPER, lcNo);
    }

    /** 查询信用证下最大批次序号，无批次返回 0。 */
    public int maxBatchSeq(String lcNo) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(batch_seq),0) FROM presentation_batch WHERE lc_no = ?",
                Integer.class, lcNo);
        return max == null ? 0 : max;
    }

    /** 记录拒付通知：写入通知时刻并锁定为 REJECTED（仅当审单中），单据状态随通知锁定。 */
    public int markRejected(String presentationNo, LocalDateTime noticeAt, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE presentation_batch
                   SET status = 'REJECTED', notice_at_utc = ?, active_slot = NULL, updated_at_utc = ?
                 WHERE presentation_no = ? AND status = 'UNDER_REVIEW'
                """, Timestamp.valueOf(noticeAt), Timestamp.valueOf(now), presentationNo);
    }

    /**
     * 标记接受（放弃不符点接受 / 超期视为接受共用）：清空 active_slot。
     * 仅在当前状态属于 allowedFrom 时生效。
     */
    public int markAccepted(String presentationNo, PresentationStatus newStatus,
            List<PresentationStatus> allowedFrom, LocalDateTime acceptedAt, LocalDateTime now) {
        String inSql = String.join(",", allowedFrom.stream().map(s -> "?").toList());
        return jdbcTemplate.update("""
                UPDATE presentation_batch
                   SET status = ?, accepted_at_utc = ?, active_slot = NULL, updated_at_utc = ?
                 WHERE presentation_no = ? AND status IN (%s)
                """.formatted(inSql),
                ps -> {
                    int i = 1;
                    ps.setString(i++, newStatus.name());
                    ps.setTimestamp(i++, Timestamp.valueOf(acceptedAt));
                    ps.setTimestamp(i++, Timestamp.valueOf(now));
                    ps.setString(i++, presentationNo);
                    for (PresentationStatus s : allowedFrom) {
                        ps.setString(i++, s.name());
                    }
                });
    }

    /** 标记付款终态（仅 ACCEPTED/DEEMED_ACCEPTED 可付款）。 */
    public int markPaid(String presentationNo, LocalDateTime paidAt, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE presentation_batch
                   SET status = 'PAID', paid_at_utc = ?, updated_at_utc = ?
                 WHERE presentation_no = ? AND status IN ('ACCEPTED','DEEMED_ACCEPTED')
                """, Timestamp.valueOf(paidAt), Timestamp.valueOf(now), presentationNo);
    }

    /** 标记退单终态（仅 REJECTED 可退单）。 */
    public int markRefunded(String presentationNo, LocalDateTime refundedAt, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE presentation_batch
                   SET status = 'REFUNDED', refunded_at_utc = ?, active_slot = NULL, updated_at_utc = ?
                 WHERE presentation_no = ? AND status = 'REJECTED'
                """, Timestamp.valueOf(refundedAt), Timestamp.valueOf(now), presentationNo);
    }

    /** 改单重交：旧批次置为 SUPERSEDED 并释放审单中位置（仅 REJECTED 可被取代）。 */
    public int markSuperseded(Long presentationId, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE presentation_batch
                   SET status = 'SUPERSEDED', active_slot = NULL, updated_at_utc = ?
                 WHERE id = ? AND status = 'REJECTED'
                """, Timestamp.valueOf(now), presentationId);
    }

    /** 超期仍处于审单中的批次自动转为视为接受。 */
    public int autoDeemAccepted(LocalDateTime deadlineBefore, LocalDateTime now) {
        return jdbcTemplate.update("""
                UPDATE presentation_batch
                   SET status = 'DEEMED_ACCEPTED', accepted_at_utc = ?, active_slot = NULL,
                       updated_at_utc = ?
                 WHERE status = 'UNDER_REVIEW' AND deadline_utc < ?
                """, Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(deadlineBefore));
    }

    private static final String BASE_SELECT = """
            SELECT id, presentation_no, lc_no, batch_seq, supersedes_id, status, draw_amount,
                   docs_json, received_at_utc, deadline_utc, notice_at_utc, accepted_at_utc,
                   paid_at_utc, refunded_at_utc, active_slot, created_at_utc, updated_at_utc
              FROM presentation_batch
            """;

    static final RowMapper<PresentationBatch> ROW_MAPPER = (rs, rowNum) -> {
        PresentationBatch b = new PresentationBatch();
        b.setId(rs.getLong("id"));
        b.setPresentationNo(rs.getString("presentation_no"));
        b.setLcNo(rs.getString("lc_no"));
        b.setBatchSeq(rs.getInt("batch_seq"));
        long supersedes = rs.getLong("supersedes_id");
        b.setSupersedesId(rs.wasNull() ? null : supersedes);
        b.setStatus(PresentationStatus.valueOf(rs.getString("status")));
        b.setDrawAmount(rs.getBigDecimal("draw_amount"));
        b.setDocsJson(rs.getString("docs_json"));
        b.setReceivedAtUtc(rs.getTimestamp("received_at_utc").toLocalDateTime());
        b.setDeadlineUtc(rs.getTimestamp("deadline_utc").toLocalDateTime());
        b.setNoticeAtUtc(toLdt(rs.getTimestamp("notice_at_utc")));
        b.setAcceptedAtUtc(toLdt(rs.getTimestamp("accepted_at_utc")));
        b.setPaidAtUtc(toLdt(rs.getTimestamp("paid_at_utc")));
        b.setRefundedAtUtc(toLdt(rs.getTimestamp("refunded_at_utc")));
        long slot = rs.getLong("active_slot");
        b.setActiveSlot(rs.wasNull() ? null : slot);
        b.setCreatedAtUtc(rs.getTimestamp("created_at_utc").toLocalDateTime());
        b.setUpdatedAtUtc(rs.getTimestamp("updated_at_utc").toLocalDateTime());
        return b;
    };

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
