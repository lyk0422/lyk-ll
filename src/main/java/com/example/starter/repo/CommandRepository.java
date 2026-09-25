package com.example.starter.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.CommandRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 限电指令数据访问。执行中唯一性由 active_farm_id 唯一索引保证。
 */
@Repository
public class CommandRepository {

    private static final String COLUMNS =
            "id, farm_id, request_id, fingerprint, target_kw, status, issued_at, sealed_at";

    private final JdbcTemplate jdbc;

    public CommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入执行中指令；active_farm_id 唯一索引冲突时抛出 DuplicateKeyException。
     */
    public long insert(long farmId, String requestId, String fingerprint, int targetKw,
                       LocalDateTime issuedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO curtailment_command (farm_id, request_id, fingerprint, target_kw,"
                            + " status, active_farm_id, issued_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, farmId);
            ps.setString(2, requestId);
            ps.setString(3, fingerprint);
            ps.setInt(4, targetKw);
            ps.setString(5, CommandRow.STATUS_EXECUTING);
            ps.setLong(6, farmId);
            ps.setTimestamp(7, Timestamp.valueOf(issuedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<CommandRow> findById(long id) {
        return queryOne("SELECT " + COLUMNS + " FROM curtailment_command WHERE id = ?", id);
    }

    public Optional<CommandRow> findByRequestId(String requestId) {
        return queryOne("SELECT " + COLUMNS + " FROM curtailment_command WHERE request_id = ?", requestId);
    }

    public Optional<CommandRow> findExecutingByFarmId(long farmId) {
        return queryOne("SELECT " + COLUMNS
                + " FROM curtailment_command WHERE farm_id = ? AND status = 'EXECUTING'", farmId);
    }

    /**
     * 作废指定执行中指令（同事务内与新指令生效一起提交）。
     */
    public int supersede(long id) {
        return jdbc.update("UPDATE curtailment_command SET status = ?, active_farm_id = NULL"
                        + " WHERE id = ? AND status = ?",
                CommandRow.STATUS_SUPERSEDED, id, CommandRow.STATUS_EXECUTING);
    }

    /**
     * 作废风场当前执行中指令（同事务内与新指令生效一起提交）。
     */
    public int supersedeExecuting(long farmId) {
        return jdbc.update("UPDATE curtailment_command SET status = ?, active_farm_id = NULL"
                        + " WHERE farm_id = ? AND status = ?",
                CommandRow.STATUS_SUPERSEDED, farmId, CommandRow.STATUS_EXECUTING);
    }

    /**
     * 日结封存；仅未封存时生效，返回受影响行数。
     */
    public int seal(long id, LocalDateTime sealedAt) {
        return jdbc.update("UPDATE curtailment_command SET status = ?, sealed_at = ?,"
                        + " active_farm_id = NULL WHERE id = ? AND status <> ?",
                CommandRow.STATUS_SEALED, Timestamp.valueOf(sealedAt), id, CommandRow.STATUS_SEALED);
    }

    public int countExecutingByFarmId(long farmId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtailment_command WHERE farm_id = ? AND status = 'EXECUTING'",
                Integer.class, farmId);
        return count == null ? 0 : count;
    }

    private Optional<CommandRow> queryOne(String sql, Object... args) {
        List<CommandRow> rows = jdbc.query(sql, (rs, i) -> new CommandRow(
                rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getString(6), rs.getTimestamp(7).toLocalDateTime(),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toLocalDateTime()), args);
        return rows.stream().findFirst();
    }
}
