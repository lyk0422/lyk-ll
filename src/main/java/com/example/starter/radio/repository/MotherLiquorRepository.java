package com.example.starter.radio.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

import com.example.starter.radio.model.MotherLiquor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 母液批次数据访问。剩余活度扣减与发药在同一事务内完成。
 */
@Repository
public class MotherLiquorRepository {

    private final JdbcTemplate jdbc;

    public MotherLiquorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String batchCode, long nuclideId, double initialActivityBq,
                       long referenceTimeEpoch, long createdAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO mother_liquor (batch_code, nuclide_id, initial_activity_bq, reference_time_epoch,"
                            + " remaining_activity_bq, remaining_time_epoch, status, created_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchCode);
            ps.setLong(2, nuclideId);
            ps.setDouble(3, initialActivityBq);
            ps.setLong(4, referenceTimeEpoch);
            ps.setDouble(5, initialActivityBq);
            ps.setLong(6, referenceTimeEpoch);
            ps.setLong(7, createdAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<MotherLiquor> findById(long id) {
        return jdbc.query("SELECT * FROM mother_liquor WHERE id = ?",
                this::map, id).stream().findFirst();
    }

    /**
     * 发药事务内扣减剩余活度：更新剩余活度及其对应时刻。
     */
    public int updateRemaining(long id, double remainingActivityBq, long remainingTimeEpoch) {
        return jdbc.update(
                "UPDATE mother_liquor SET remaining_activity_bq = ?, remaining_time_epoch = ? WHERE id = ?",
                remainingActivityBq, remainingTimeEpoch, id);
    }

    private MotherLiquor map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new MotherLiquor(
                rs.getLong("id"),
                rs.getString("batch_code"),
                rs.getLong("nuclide_id"),
                rs.getDouble("initial_activity_bq"),
                rs.getLong("reference_time_epoch"),
                rs.getDouble("remaining_activity_bq"),
                rs.getLong("remaining_time_epoch"),
                rs.getString("status"));
    }
}
