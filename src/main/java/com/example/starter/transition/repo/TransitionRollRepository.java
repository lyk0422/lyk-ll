package com.example.starter.transition.repo;

import com.example.starter.transition.model.TransitionRoll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 过渡纸卷仓储。
 */
@Repository
public class TransitionRollRepository {

    private static final RowMapper<TransitionRoll> MAPPER = (rs, n) -> {
        TransitionRoll r = new TransitionRoll();
        r.setId(rs.getLong("id"));
        r.setRollNo(rs.getString("roll_no"));
        r.setOrderNo(rs.getString("order_no"));
        r.setPaperMachine(rs.getString("paper_machine"));
        r.setLengthM(rs.getBigDecimal("length_m"));
        r.setVerdict(rs.getString("verdict"));
        r.setWarehouseStatus(rs.getString("warehouse_status"));
        r.setEligibleReexam(rs.getInt("eligible_reexam") != 0);
        r.setReexamCount(rs.getInt("reexam_count"));
        r.setRegisteredAt(rs.getTimestamp("registered_at").toLocalDateTime());
        Timestamp changed = rs.getTimestamp("verdict_changed_at");
        r.setVerdictChangedAt(changed == null ? null : changed.toLocalDateTime());
        return r;
    };

    private final JdbcTemplate jdbc;

    public TransitionRollRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 强制降级登记：verdict=DOWNGRADED，warehouse_status=NON_GENUINE。 */
    public void insertDowngraded(TransitionRoll roll, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO transition_roll
                            (roll_no, order_no, paper_machine, length_m, verdict,
                             warehouse_status, eligible_reexam, reexam_count, registered_at)
                        VALUES (?, ?, ?, ?, 'DOWNGRADED', 'NON_GENUINE', 0, 0, ?)
                        """,
                roll.getRollNo(), roll.getOrderNo(), roll.getPaperMachine(),
                roll.getLengthM(), Timestamp.valueOf(now));
    }

    public Optional<TransitionRoll> findByRollNo(String rollNo) {
        List<TransitionRoll> list = jdbc.query(
                "SELECT * FROM transition_roll WHERE roll_no = ?", MAPPER, rollNo);
        return list.stream().findFirst();
    }

    /**
     * 行锁读取：回收登记与复议改判并发时在同一纸卷行上串行化，
     * 保证"先登记后改判则额度失效、先改判后登记则拒绝"的唯一一致次序。
     */
    public Optional<TransitionRoll> findByRollNoForUpdate(String rollNo) {
        List<TransitionRoll> list = jdbc.query(
                "SELECT * FROM transition_roll WHERE roll_no = ? FOR UPDATE", MAPPER, rollNo);
        return list.stream().findFirst();
    }

    public TransitionRoll getByRollNo(String rollNo) {
        return findByRollNo(rollNo).orElseThrow(() ->
                new IllegalArgumentException("过渡纸卷不存在: " + rollNo));
    }

    public List<TransitionRoll> findByOrderNo(String orderNo) {
        return jdbc.query(
                "SELECT * FROM transition_roll WHERE order_no = ? ORDER BY id",
                MAPPER, orderNo);
    }

    /** 首次检验回写复议资格，不改判定（仍为降级）。 */
    public void updateInitialEligibility(String rollNo, boolean eligible) {
        jdbc.update("UPDATE transition_roll SET eligible_reexam = ? WHERE roll_no = ?",
                eligible ? 1 : 0, rollNo);
    }

    /** 复议改判：终态 UPGRADED、入正品库、复议次数 +1。 */
    public int applyUpgrade(String rollNo, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE transition_roll
                        SET verdict = 'UPGRADED', warehouse_status = 'GENUINE',
                            reexam_count = reexam_count + 1, verdict_changed_at = ?
                        WHERE roll_no = ? AND verdict = 'DOWNGRADED' AND reexam_count = 0
                        """,
                Timestamp.valueOf(now), rollNo);
    }

    /** 复议驳回：仅复议次数 +1，终态保持降级、留非正品库。 */
    public int applyReexamReject(String rollNo) {
        return jdbc.update("""
                        UPDATE transition_roll
                        SET reexam_count = reexam_count + 1
                        WHERE roll_no = ? AND verdict = 'DOWNGRADED' AND reexam_count = 0
                        """,
                rollNo);
    }
}
