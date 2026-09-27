package com.example.starter.repository;

import com.example.starter.domain.ValveSeal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 阀门铅封台账数据访问：登记、复封取代与历史封号链查询。
 */
@Repository
public class ValveSealRepository {

    private static final RowMapper<ValveSeal> MAPPER = (rs, n) -> new ValveSeal(
            rs.getLong("id"), rs.getLong("valve_id"), rs.getString("seal_no"),
            rs.getInt("valve_version"), rs.getTimestamp("sealed_at").toInstant(),
            rs.getString("operator_a"), rs.getString("operator_b"),
            ValveSeal.SealStatus.valueOf(rs.getString("status")),
            rs.getString("request_id"), rs.getString("fingerprint"), rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private static final String COLUMNS = "id, valve_id, seal_no, valve_version, sealed_at,"
            + " operator_a, operator_b, status, request_id, fingerprint, version, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public ValveSealRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入铅封台账行并返回生成的主键。
     */
    public long insert(ValveSeal seal) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO valve_seal"
                            + " (valve_id, seal_no, valve_version, sealed_at, operator_a, operator_b,"
                            + " status, request_id, fingerprint, version, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, seal.valveId());
            ps.setString(2, seal.sealNo());
            ps.setInt(3, seal.valveVersion());
            ps.setTimestamp(4, Timestamp.from(seal.sealedAt()));
            ps.setString(5, seal.operatorA());
            ps.setString(6, seal.operatorB());
            ps.setString(7, seal.status().name());
            ps.setString(8, seal.requestId());
            ps.setString(9, seal.fingerprint());
            ps.setInt(10, seal.version());
            ps.setTimestamp(11, Timestamp.from(seal.createdAt()));
            ps.setTimestamp(12, Timestamp.from(seal.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等键查询台账行。
     */
    public Optional<ValveSeal> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM valve_seal WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 查询阀门当前有效（ACTIVE）铅封，无则空。
     */
    public Optional<ValveSeal> findActiveByValveId(long valveId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM valve_seal"
                        + " WHERE valve_id = ? AND status = 'ACTIVE'",
                MAPPER, valveId).stream().findFirst();
    }

    /**
     * 查询阀门完整封号链（含历史），按主键升序。
     */
    public List<ValveSeal> findByValveId(long valveId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM valve_seal WHERE valve_id = ? ORDER BY id",
                MAPPER, valveId);
    }

    /**
     * 复封时将旧铅封标记为 SUPERSEDED 并行版本加 1；乐观锁不匹配时返回 false。
     */
    public boolean supersede(long sealId, int expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("UPDATE valve_seal SET status = 'SUPERSEDED',"
                        + " version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                Timestamp.from(updatedAt), sealId, expectedVersion);
        return updated == 1;
    }
}
