package com.example.starter.fermentation.repository;

import com.example.starter.fermentation.domain.BatchStatus;
import com.example.starter.fermentation.domain.FermentationBatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 发酵批次数据访问。所有 SQL 参数化；并发入罐由 uk_batch_active_tank 唯一约束兜底。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<FermentationBatch> MAPPER = (rs, rowNum) -> new FermentationBatch(
            rs.getLong("id"),
            rs.getString("batch_no"),
            rs.getLong("tank_id"),
            rs.getString("curve_code"),
            rs.getInt("curve_version"),
            BatchStatus.valueOf(rs.getString("status")),
            toLdt(rs.getTimestamp("filled_at")),
            toLdt(rs.getTimestamp("main_ferment_started_at")),
            toLdt(rs.getTimestamp("cooling_started_at")),
            toLdt(rs.getTimestamp("maturation_started_at")),
            rs.getInt("maturation_extended") != 0,
            toLdt(rs.getTimestamp("judged_at")),
            toLdt(rs.getTimestamp("transferred_at")));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /**
     * 按罐编号查罐 ID，不存在返回空。
     */
    public Optional<Long> findTankIdByCode(String tankCode) {
        return jdbc.query("SELECT id FROM fermentation_tank WHERE tank_code = ?",
                rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(), tankCode);
    }

    /**
     * 创建批次（满罐），active_tank_key 占用发酵罐；同罐并发由唯一约束拒绝。
     *
     * @return 生成的批次 ID
     */
    public long insert(String batchNo, long tankId, String curveCode, int curveVersion,
                       LocalDateTime filledAt, String activeTankKey) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO fermentation_batch (batch_no, tank_id, curve_code, curve_version, status,"
                            + " filled_at, maturation_extended, active_tank_key)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setLong(2, tankId);
            ps.setString(3, curveCode);
            ps.setInt(4, curveVersion);
            ps.setString(5, BatchStatus.FILLED.name());
            ps.setTimestamp(6, Timestamp.valueOf(filledAt));
            ps.setString(7, activeTankKey);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<FermentationBatch> findById(long id) {
        return jdbc.query("SELECT * FROM fermentation_batch WHERE id = ?",
                rs -> rs.next() ? Optional.of(MAPPER.mapRow(rs, 1)) : Optional.empty(), id);
    }

    /**
     * 开始主酵：FILLED → MAIN_FERMENT。
     */
    public void startMainFerment(long id, LocalDateTime startedAt) {
        jdbc.update("UPDATE fermentation_batch SET status = ?, main_ferment_started_at = ? WHERE id = ?",
                BatchStatus.MAIN_FERMENT.name(), Timestamp.valueOf(startedAt), id);
    }

    /**
     * 降温指令与曲线版本切换同事务更新：MAIN_FERMENT → COOLING，并切换到新曲线版本。
     */
    public void applyCooling(long id, int newCurveVersion, LocalDateTime instructedAt) {
        jdbc.update("UPDATE fermentation_batch SET status = ?, curve_version = ?, cooling_started_at = ?"
                        + " WHERE id = ?",
                BatchStatus.COOLING.name(), newCurveVersion, Timestamp.valueOf(instructedAt), id);
    }

    /**
     * 开始后熟：COOLING → MATURATION。
     */
    public void startMaturation(long id, LocalDateTime startedAt) {
        jdbc.update("UPDATE fermentation_batch SET status = ?, maturation_started_at = ? WHERE id = ?",
                BatchStatus.MATURATION.name(), Timestamp.valueOf(startedAt), id);
    }

    /**
     * 延长后熟一次：置延长标记。
     */
    public void markMaturationExtended(long id) {
        jdbc.update("UPDATE fermentation_batch SET maturation_extended = 1 WHERE id = ?", id);
    }

    /**
     * 记录判定结果：更新状态（JUDGED / REVIEW / 保持 MATURATION）与判定时间。
     */
    public void updateJudgement(long id, BatchStatus newStatus, LocalDateTime judgedAt) {
        jdbc.update("UPDATE fermentation_batch SET status = ?, judged_at = ? WHERE id = ?",
                newStatus.name(), Timestamp.valueOf(judgedAt), id);
    }

    /**
     * 清酒转出：JUDGED → TRANSFERRED，释放发酵罐占用键。
     */
    public void completeTransfer(long id, LocalDateTime transferredAt) {
        jdbc.update("UPDATE fermentation_batch SET status = ?, transferred_at = ?, active_tank_key = NULL"
                        + " WHERE id = ?",
                BatchStatus.TRANSFERRED.name(), Timestamp.valueOf(transferredAt), id);
    }

    /**
     * 统计指定罐的进行中批次数（用于测试断言唯一占用）。
     */
    public int countActiveByTankKey(String tankCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch WHERE active_tank_key = ?", Integer.class, tankCode);
        return count == null ? 0 : count;
    }
}
