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
 * 阀门铅封台账数据访问。
 */
@Repository
public class ValveSealRepository {

    private static final RowMapper<ValveSeal> MAPPER = (rs, n) -> new ValveSeal(
            rs.getLong("id"), rs.getLong("valve_id"), rs.getString("seal_no"),
            rs.getInt("valve_version"),
            ValveSeal.SealStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getTimestamp("sealed_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("resealed_at")).map(Timestamp::toInstant).orElse(null),
            rs.getString("request_id"), rs.getString("fingerprint"));

    private static final String COLUMNS = "id, valve_id, seal_no, valve_version, status, version,"
            + " sealed_at, resealed_at, request_id, fingerprint";

    private final JdbcTemplate jdbc;

    public ValveSealRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入铅封台账并返回生成的主键。
     */
    public long insert(ValveSeal seal) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO valve_seal"
                            + " (valve_id, seal_no, valve_version, status, version,"
                            + " sealed_at, resealed_at, request_id, fingerprint)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, seal.valveId());
            ps.setString(2, seal.sealNo());
            ps.setInt(3, seal.valveVersion());
            ps.setString(4, seal.status().name());
            ps.setInt(5, seal.version());
            ps.setTimestamp(6, Timestamp.from(seal.sealedAt()));
            ps.setTimestamp(7, seal.resealedAt() == null ? null : Timestamp.from(seal.resealedAt()));
            ps.setString(8, seal.requestId());
            ps.setString(9, seal.fingerprint());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按 ID 查询铅封台账。
     */
    public Optional<ValveSeal> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM valve_seal WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按幂等键查询铅封台账（登记或复封请求的键）。
     */
    public Optional<ValveSeal> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM valve_seal WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 按铅封号查询台账（铅封号全局唯一，含历史）。
     */
    public Optional<ValveSeal> findBySealNo(String sealNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM valve_seal WHERE seal_no = ?",
                MAPPER, sealNo).stream().findFirst();
    }

    /**
     * 查询阀门当前完整有效（ACTIVE）的铅封，无则空。
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
     * 复封时原子终结旧铅封：仅当版本一致且仍 ACTIVE 时生效，返回更新行数。
     */
    public int markResealed(long id, int expectedVersion, Instant resealedAt) {
        return jdbc.update("UPDATE valve_seal SET status = 'RESEALED', resealed_at = ?,"
                        + " version = version + 1 WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                Timestamp.from(resealedAt), id, expectedVersion);
    }
}
