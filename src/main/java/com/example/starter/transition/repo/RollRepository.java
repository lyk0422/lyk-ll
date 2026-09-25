package com.example.starter.transition.repo;

import com.example.starter.transition.po.TransitionRoll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 过渡纸卷数据访问；判定推进使用带当前状态条件的 CAS 更新，保证并发唯一终态。
 */
@Repository
public class RollRepository {

    private static final String COLUMNS = "id, roll_no, order_no, machine_id, length_m, final_grade, "
            + "inspection_status, gate_passed, measured_basis_weight, measured_ash_level, measured_tensile, "
            + "appeal_count, created_at, inspected_at, appealed_at";

    private static final RowMapper<TransitionRoll> MAPPER = new RollRowMapper();

    private final JdbcTemplate jdbc;

    public RollRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TransitionRoll roll) {
        jdbc.update("INSERT INTO transition_roll (roll_no, order_no, machine_id, length_m, final_grade, "
                        + "inspection_status, appeal_count) VALUES (?, ?, ?, ?, ?, ?, 0)",
                roll.rollNo, roll.orderNo, roll.machineId, roll.lengthM,
                roll.finalGrade, roll.inspectionStatus);
    }

    public Optional<TransitionRoll> findByRollNo(String rollNo) {
        List<TransitionRoll> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM transition_roll WHERE roll_no = ?", MAPPER, rollNo);
        return list.stream().findFirst();
    }

    /** 行锁读取，用于复议与首次判定的并发互斥。 */
    public Optional<TransitionRoll> findByRollNoForUpdate(String rollNo) {
        List<TransitionRoll> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM transition_roll WHERE roll_no = ? FOR UPDATE",
                MAPPER, rollNo);
        return list.stream().findFirst();
    }

    public List<TransitionRoll> findByOrderNo(String orderNo) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM transition_roll WHERE order_no = ? ORDER BY id",
                MAPPER, orderNo);
    }

    /**
     * 首次检验判定：仅 PENDING 可推进，写入合成指标与门槛结论，终态强制降级。
     */
    public int casInspect(TransitionRoll roll, LocalDateTime inspectedAt) {
        return jdbc.update("UPDATE transition_roll SET inspection_status = 'INSPECTED', gate_passed = ?, "
                        + "measured_basis_weight = ?, measured_ash_level = ?, measured_tensile = ?, "
                        + "inspected_at = ? WHERE roll_no = ? AND inspection_status = 'PENDING'",
                roll.gatePassed ? 1 : 0, roll.measuredBasisWeight, roll.measuredAshLevel,
                roll.measuredTensile, Timestamp.valueOf(inspectedAt), roll.rollNo);
    }

    /**
     * 复议改判正品：仅已检验、未复议、达门槛的卷可推进一次。
     */
    public int casAppeal(String rollNo, LocalDateTime appealedAt) {
        return jdbc.update("UPDATE transition_roll SET final_grade = 'QUALIFIED', appeal_count = 1, "
                        + "appealed_at = ? WHERE roll_no = ? AND inspection_status = 'INSPECTED' "
                        + "AND appeal_count = 0 AND gate_passed = 1",
                Timestamp.valueOf(appealedAt), rollNo);
    }

    private static final class RollRowMapper implements RowMapper<TransitionRoll> {
        @Override
        public TransitionRoll mapRow(ResultSet rs, int rowNum) throws SQLException {
            TransitionRoll r = new TransitionRoll();
            r.id = rs.getLong("id");
            r.rollNo = rs.getString("roll_no");
            r.orderNo = rs.getString("order_no");
            r.machineId = rs.getString("machine_id");
            r.lengthM = rs.getBigDecimal("length_m");
            r.finalGrade = rs.getString("final_grade");
            r.inspectionStatus = rs.getString("inspection_status");
            int gate = rs.getInt("gate_passed");
            r.gatePassed = rs.wasNull() ? null : gate == 1;
            r.measuredBasisWeight = rs.getBigDecimal("measured_basis_weight");
            int ash = rs.getInt("measured_ash_level");
            r.measuredAshLevel = rs.wasNull() ? null : ash;
            r.measuredTensile = rs.getBigDecimal("measured_tensile");
            r.appealCount = rs.getInt("appeal_count");
            r.createdAt = rs.getTimestamp("created_at").toLocalDateTime();
            Timestamp inspected = rs.getTimestamp("inspected_at");
            r.inspectedAt = inspected == null ? null : inspected.toLocalDateTime();
            Timestamp appealed = rs.getTimestamp("appealed_at");
            r.appealedAt = appealed == null ? null : appealed.toLocalDateTime();
            return r;
        }
    }
}
