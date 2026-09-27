package com.example.starter.survey.data;

import com.example.starter.survey.domain.FlagReport;
import com.example.starter.survey.domain.FlagReportItem;
import com.example.starter.survey.domain.FlagReportRevision;
import com.example.starter.survey.domain.ItemConclusion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 外部检查报告（头 + 修订 + 逐项结论）数据访问。
 * 报告编号全局唯一；更正只追加新修订并推进 current_revision，
 * UPDATE 带旧版本条件做乐观并发控制，行锁（FOR UPDATE）与发证一致性校验互斥。
 */
@Repository
public class FlagReportRepository {

    private final JdbcTemplate jdbc;

    public FlagReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FlagReport> REPORT_MAPPER = (rs, n) -> new FlagReport(
            rs.getLong("id"),
            rs.getString("report_no"),
            rs.getString("ship_imo"),
            rs.getString("flag_code"),
            rs.getInt("current_revision"),
            rs.getTimestamp("created_at").toInstant()
    );

    private static final RowMapper<FlagReportRevision> REVISION_MAPPER = (rs, n) -> new FlagReportRevision(
            rs.getLong("id"),
            rs.getLong("report_id"),
            rs.getInt("revision"),
            rs.getTimestamp("completed_at").toInstant(),
            rs.getString("attestation"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toInstant()
    );

    private static final RowMapper<FlagReportItem> ITEM_MAPPER = (rs, n) -> new FlagReportItem(
            rs.getLong("id"),
            rs.getLong("revision_id"),
            rs.getString("item_code"),
            ItemConclusion.valueOf(rs.getString("conclusion"))
    );

    /** 插入报告头（初始修订号为 1）。 */
    public FlagReport insertReport(String reportNo, String shipImo, String flagCode, Instant now) {
        jdbc.update("""
                        INSERT INTO flag_report (report_no, ship_imo, flag_code, current_revision, created_at)
                        VALUES (?, ?, ?, 1, ?)
                        """,
                reportNo, shipImo, flagCode, Timestamp.from(now));
        return findByReportNo(reportNo).orElseThrow();
    }

    /** 追加报告修订。 */
    public FlagReportRevision insertRevision(long reportId, int revision, Instant completedAt,
                                             String attestation, String requestId,
                                             String fingerprint, Instant now) {
        jdbc.update("""
                        INSERT INTO flag_report_revision
                            (report_id, revision, completed_at, attestation, request_id, fingerprint, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                reportId, revision, Timestamp.from(completedAt), attestation,
                requestId, fingerprint, Timestamp.from(now));
        return findRevision(reportId, revision).orElseThrow();
    }

    /** 追加修订的逐项结论。 */
    public void insertItem(long revisionId, String itemCode, ItemConclusion conclusion) {
        jdbc.update("""
                        INSERT INTO flag_report_item (revision_id, item_code, conclusion)
                        VALUES (?, ?, ?)
                        """,
                revisionId, itemCode, conclusion.name());
    }

    /**
     * 推进当前修订号（乐观并发控制）。
     *
     * @return true 表示从 expectedVersion 成功推进；false 表示版本已被并发修改
     */
    public boolean advanceRevision(long reportId, int expectedVersion) {
        int updated = jdbc.update("""
                        UPDATE flag_report SET current_revision = current_revision + 1
                        WHERE id = ? AND current_revision = ?
                        """,
                reportId, expectedVersion);
        return updated == 1;
    }

    /** 按报告编号查找。 */
    public Optional<FlagReport> findByReportNo(String reportNo) {
        return jdbc.query("SELECT * FROM flag_report WHERE report_no = ?", REPORT_MAPPER, reportNo)
                .stream().findFirst();
    }

    /** 按主键查找。 */
    public Optional<FlagReport> findById(long id) {
        return jdbc.query("SELECT * FROM flag_report WHERE id = ?", REPORT_MAPPER, id)
                .stream().findFirst();
    }

    /** 按主键查找并加行锁（更正与发证一致性校验互斥用）。 */
    public Optional<FlagReport> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM flag_report WHERE id = ? FOR UPDATE", REPORT_MAPPER, id)
                .stream().findFirst();
    }

    /** 按报告与修订号查找修订。 */
    public Optional<FlagReportRevision> findRevision(long reportId, int revision) {
        return jdbc.query("SELECT * FROM flag_report_revision WHERE report_id = ? AND revision = ?",
                        REVISION_MAPPER, reportId, revision)
                .stream().findFirst();
    }

    /** 列出报告全部修订（按修订号升序）。 */
    public List<FlagReportRevision> findRevisions(long reportId) {
        return jdbc.query("SELECT * FROM flag_report_revision WHERE report_id = ? ORDER BY revision",
                REVISION_MAPPER, reportId);
    }

    /** 列出修订的逐项结论（按项目代码排序）。 */
    public List<FlagReportItem> findItems(long revisionId) {
        return jdbc.query("SELECT * FROM flag_report_item WHERE revision_id = ? ORDER BY item_code",
                ITEM_MAPPER, revisionId);
    }
}
