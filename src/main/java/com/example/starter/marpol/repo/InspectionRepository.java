package com.example.starter.marpol.repo;

import com.example.starter.marpol.domain.Conclusion;
import com.example.starter.marpol.domain.Defect;
import com.example.starter.marpol.domain.DefectStatus;
import com.example.starter.marpol.domain.Inspection;
import com.example.starter.marpol.domain.InspectionItem;
import com.example.starter.marpol.domain.InspectionStatus;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;

/**
 * 港口国核查、逐条结论与缺陷项数据访问。
 */
@Repository
public class InspectionRepository {

    private final JdbcTemplate jdbc;

    public InspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertInspection(Inspection inspection) {
        jdbc.update(
                "INSERT INTO inspection "
                        + "(inspection_no, ship_id, port_code, inspect_date, status, review_of, "
                        + "issued_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                inspection.getInspectionNo(), inspection.getShipId(), inspection.getPortCode(),
                Date.valueOf(inspection.getInspectDate()), inspection.getStatus().name(),
                inspection.getReviewOf(),
                inspection.getIssuedAt() == null ? null : Timestamp.from(inspection.getIssuedAt()),
                Timestamp.from(inspection.getCreatedAt()));
    }

    public Optional<Inspection> find(String inspectionNo) {
        List<Inspection> list = jdbc.query(
                "SELECT inspection_no, ship_id, port_code, inspect_date, status, review_of, "
                        + "issued_at, created_at FROM inspection WHERE inspection_no = ?",
                inspectionRowMapper(), inspectionNo);
        return list.stream().findFirst();
    }

    public List<Inspection> findByShip(String shipId) {
        return jdbc.query(
                "SELECT inspection_no, ship_id, port_code, inspect_date, status, review_of, "
                        + "issued_at, created_at FROM inspection WHERE ship_id = ? ORDER BY id",
                inspectionRowMapper(), shipId);
    }

    /**
     * CAS 签发：仅 OPEN 可置 ISSUED。
     *
     * @return 0 表示已被并发签发（调用方按幂等成功处理）
     */
    public int markIssued(String inspectionNo, Instant issuedAt) {
        return jdbc.update(
                "UPDATE inspection SET status = 'ISSUED', issued_at = ? "
                        + "WHERE inspection_no = ? AND status = 'OPEN'",
                Timestamp.from(issuedAt), inspectionNo);
    }

    public void insertItem(InspectionItem item) {
        PreparedStatementCreator creator = con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO inspection_item "
                            + "(inspection_no, record_no, record_version, conclusion, evidence, "
                            + "decided_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, item.getInspectionNo());
            ps.setString(2, item.getRecordNo());
            ps.setInt(3, item.getRecordVersion());
            ps.setString(4, item.getConclusion().name());
            ps.setString(5, item.getEvidence());
            ps.setTimestamp(6, Timestamp.from(item.getDecidedAt()));
            return ps;
        };
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(creator, keyHolder);
        item.setId(keyHolder.getKey() == null ? null : keyHolder.getKey().longValue());
    }

    public Optional<InspectionItem> findItem(String inspectionNo, String recordNo) {
        List<InspectionItem> list = jdbc.query(
                "SELECT id, inspection_no, record_no, record_version, conclusion, evidence, "
                        + "decided_at FROM inspection_item "
                        + "WHERE inspection_no = ? AND record_no = ?",
                itemRowMapper(), inspectionNo, recordNo);
        return list.stream().findFirst();
    }

    public List<InspectionItem> findItems(String inspectionNo) {
        return jdbc.query(
                "SELECT id, inspection_no, record_no, record_version, conclusion, evidence, "
                        + "decided_at FROM inspection_item WHERE inspection_no = ? ORDER BY id",
                itemRowMapper(), inspectionNo);
    }

    public void insertDefect(Defect defect) {
        PreparedStatementCreator creator = con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO defect (inspection_no, item_id, record_no, record_version, "
                            + "deadline, status, closed_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, defect.getInspectionNo());
            ps.setLong(2, defect.getItemId());
            ps.setString(3, defect.getRecordNo());
            ps.setInt(4, defect.getRecordVersion());
            ps.setDate(5, Date.valueOf(defect.getDeadline()));
            ps.setString(6, defect.getStatus().name());
            ps.setTimestamp(7, defect.getClosedAt() == null
                    ? null : Timestamp.from(defect.getClosedAt()));
            ps.setTimestamp(8, Timestamp.from(defect.getCreatedAt()));
            return ps;
        };
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(creator, keyHolder);
        defect.setId(keyHolder.getKey() == null ? null : keyHolder.getKey().longValue());
    }

    public Optional<Defect> findDefectByItem(long itemId) {
        List<Defect> list = jdbc.query(
                "SELECT id, inspection_no, item_id, record_no, record_version, deadline, status, "
                        + "closed_at, created_at FROM defect WHERE item_id = ?",
                defectRowMapper(), itemId);
        return list.stream().findFirst();
    }

    public List<Defect> findDefects(String inspectionNo) {
        return jdbc.query(
                "SELECT id, inspection_no, item_id, record_no, record_version, deadline, status, "
                        + "closed_at, created_at FROM defect WHERE inspection_no = ? ORDER BY id",
                defectRowMapper(), inspectionNo);
    }

    public Optional<Defect> findDefect(long defectId) {
        List<Defect> list = jdbc.query(
                "SELECT id, inspection_no, item_id, record_no, record_version, deadline, status, "
                        + "closed_at, created_at FROM defect WHERE id = ?",
                defectRowMapper(), defectId);
        return list.stream().findFirst();
    }

    /**
     * 按当前 UTC 日期把过期未关闭缺陷置为 OVERDUE，返回受影响行数。
     */
    public int markOverdueBefore(LocalDate today) {
        return jdbc.update(
                "UPDATE defect SET status = 'OVERDUE' "
                        + "WHERE status = 'OPEN' AND deadline < ?",
                Date.valueOf(today));
    }

    /**
     * 按期关闭缺陷：deadline 当天或之前关闭有效（CAS：仅 OPEN 可关闭）。
     *
     * @return 受影响行数；0 表示已关闭、已逾期或已超期
     */
    public int closeDefectOnTime(long defectId, LocalDate today, Instant closedAt) {
        return jdbc.update(
                "UPDATE defect SET status = 'CLOSED', closed_at = ? "
                        + "WHERE id = ? AND status = 'OPEN' AND deadline >= ?",
                Timestamp.from(closedAt), defectId, Date.valueOf(today));
    }

    private RowMapper<Inspection> inspectionRowMapper() {
        return (rs, n) -> {
            Inspection inspection = new Inspection();
            inspection.setInspectionNo(rs.getString("inspection_no"));
            inspection.setShipId(rs.getString("ship_id"));
            inspection.setPortCode(rs.getString("port_code"));
            Date inspectDate = rs.getDate("inspect_date");
            inspection.setInspectDate(inspectDate == null ? null : inspectDate.toLocalDate());
            inspection.setStatus(InspectionStatus.valueOf(rs.getString("status")));
            inspection.setReviewOf(rs.getString("review_of"));
            Timestamp issued = rs.getTimestamp("issued_at");
            inspection.setIssuedAt(issued == null ? null : issued.toInstant());
            Timestamp created = rs.getTimestamp("created_at");
            inspection.setCreatedAt(created == null ? null : created.toInstant());
            return inspection;
        };
    }

    private RowMapper<InspectionItem> itemRowMapper() {
        return (rs, n) -> {
            InspectionItem item = new InspectionItem();
            item.setId(rs.getLong("id"));
            item.setInspectionNo(rs.getString("inspection_no"));
            item.setRecordNo(rs.getString("record_no"));
            item.setRecordVersion(rs.getInt("record_version"));
            item.setConclusion(Conclusion.valueOf(rs.getString("conclusion")));
            item.setEvidence(rs.getString("evidence"));
            Timestamp decided = rs.getTimestamp("decided_at");
            item.setDecidedAt(decided == null ? null : decided.toInstant());
            return item;
        };
    }

    private RowMapper<Defect> defectRowMapper() {
        return (rs, n) -> {
            Defect defect = new Defect();
            defect.setId(rs.getLong("id"));
            defect.setInspectionNo(rs.getString("inspection_no"));
            defect.setItemId(rs.getLong("item_id"));
            defect.setRecordNo(rs.getString("record_no"));
            defect.setRecordVersion(rs.getInt("record_version"));
            Date deadline = rs.getDate("deadline");
            defect.setDeadline(deadline == null ? null : deadline.toLocalDate());
            defect.setStatus(DefectStatus.valueOf(rs.getString("status")));
            Timestamp closed = rs.getTimestamp("closed_at");
            defect.setClosedAt(closed == null ? null : closed.toInstant());
            Timestamp created = rs.getTimestamp("created_at");
            defect.setCreatedAt(created == null ? null : created.toInstant());
            return defect;
        };
    }
}
