package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 渔捞日志的数据访问。日志按“船＋日期＋渔区”唯一去重，明细按作业时刻归属。
 */
@Repository
public class LogbookDao {

    /** 日志行。 */
    public record LogRow(long id, String requestId, long voyageId, long vesselId,
                         LocalDate logDate, String fishingArea, boolean bycatchFlagged) {
    }

    /** 日志明细行。 */
    public record LogItemRow(long logbookId, String speciesCode, BigDecimal quantityKg, Instant operationTime) {
    }

    private final JdbcTemplate jdbc;

    public LogbookDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按去重键（船＋日期＋渔区）查询日志。 */
    public Optional<LogRow> findByKey(long vesselId, LocalDate logDate, String fishingArea) {
        List<LogRow> rows = jdbc.query(
                "SELECT id, request_id, voyage_id, vessel_id, log_date, fishing_area, bycatch_flagged"
                        + " FROM logbook_entry WHERE vessel_id = ? AND log_date = ? AND fishing_area = ?",
                (rs, n) -> new LogRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        rs.getDate(5).toLocalDate(), rs.getString(6), rs.getBoolean(7)),
                vesselId, Date.valueOf(logDate), fishingArea);
        return rows.stream().findFirst();
    }

    /** 新建日志，返回日志 ID。 */
    public long insertLog(String requestId, long voyageId, long vesselId,
                          LocalDate logDate, String fishingArea, boolean bycatchFlagged) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO logbook_entry (request_id, voyage_id, vessel_id, log_date, fishing_area,"
                            + " bycatch_flagged, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setLong(2, voyageId);
            ps.setLong(3, vesselId);
            ps.setDate(4, Date.valueOf(logDate));
            ps.setString(5, fishingArea);
            ps.setBoolean(6, bycatchFlagged);
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 记录日志明细。 */
    public void insertItems(List<LogItemRow> items) {
        jdbc.batchUpdate("INSERT INTO logbook_item (logbook_id, species_code, quantity_kg, operation_time)"
                        + " VALUES (?, ?, ?, ?)",
                items, items.size(),
                (ps, item) -> {
                    ps.setLong(1, item.logbookId());
                    ps.setString(2, item.speciesCode());
                    ps.setBigDecimal(3, item.quantityKg());
                    ps.setTimestamp(4, Timestamp.from(item.operationTime()));
                });
    }

    /** 汇总航次全部日志的分鱼种累计量（kg）。 */
    public Map<String, BigDecimal> sumBySpeciesForVoyage(long voyageId) {
        Map<String, BigDecimal> sums = new HashMap<>();
        jdbc.query("SELECT i.species_code, SUM(i.quantity_kg) FROM logbook_item i"
                        + " JOIN logbook_entry e ON e.id = i.logbook_id"
                        + " WHERE e.voyage_id = ? GROUP BY i.species_code",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        sums.put(rs.getString(1), rs.getBigDecimal(2)), voyageId);
        return sums;
    }

    /** 航次全部日志（按日期、渔区排序，日志链可复算）。 */
    public List<LogRow> listLogsForVoyage(long voyageId) {
        return jdbc.query(
                "SELECT id, request_id, voyage_id, vessel_id, log_date, fishing_area, bycatch_flagged"
                        + " FROM logbook_entry WHERE voyage_id = ? ORDER BY log_date, fishing_area",
                (rs, n) -> new LogRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        rs.getDate(5).toLocalDate(), rs.getString(6), rs.getBoolean(7)),
                voyageId);
    }
}
