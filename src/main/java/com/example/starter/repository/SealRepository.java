package com.example.starter.repository;

import com.example.starter.domain.ValveSeal;
import org.springframework.dao.DuplicateKeyException;
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
 * 平衡阀铅封台账数据访问：唯一铅封号、生效铅封与历史封号链。
 */
@Repository
public class SealRepository {

    private static final RowMapper<ValveSeal> MAPPER = (rs, n) -> new ValveSeal(
            rs.getLong("id"), rs.getLong("valve_id"), rs.getString("seal_code"),
            rs.getInt("valve_version"), ValveSeal.SealStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("sealed_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("resealed_at")).map(Timestamp::toInstant).orElse(null),
            rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS = "id, valve_id, seal_code, valve_version, status,"
            + " sealed_at, resealed_at, created_at";

    private final JdbcTemplate jdbc;

    public SealRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入铅封台账并返回主键；铅封号重复时抛出唯一约束异常。
     */
    public long insert(ValveSeal seal) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO valve_seal"
                            + " (valve_id, seal_code, valve_version, status, sealed_at, resealed_at, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, seal.valveId());
            ps.setString(2, seal.sealCode());
            ps.setInt(3, seal.valveVersion());
            ps.setString(4, seal.status().name());
            ps.setTimestamp(5, Timestamp.from(seal.sealedAt()));
            ps.setTimestamp(6, seal.resealedAt() == null ? null : Timestamp.from(seal.resealedAt()));
            ps.setTimestamp(7, Timestamp.from(seal.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询。
     */
    public Optional<ValveSeal> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM valve_seal WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按铅封号查询。
     */
    public Optional<ValveSeal> findByCode(String sealCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM valve_seal WHERE seal_code = ?", MAPPER, sealCode)
                .stream().findFirst();
    }

    /**
     * 按铅封号查询并对台账行加写锁。
     */
    public Optional<ValveSeal> lockByCode(String sealCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM valve_seal WHERE seal_code = ? FOR UPDATE",
                MAPPER, sealCode).stream().findFirst();
    }

    /**
     * 查询阀门当前生效铅封。
     */
    public Optional<ValveSeal> findActiveByValveId(long valveId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM valve_seal"
                        + " WHERE valve_id = ? AND status = 'ACTIVE'", MAPPER, valveId)
                .stream().findFirst();
    }

    /**
     * 查询阀门完整封号链（含历史封号），按主键升序。
     */
    public List<ValveSeal> findChainByValveId(long valveId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM valve_seal WHERE valve_id = ? ORDER BY id",
                MAPPER, valveId);
    }

    /**
     * 复封终结：旧铅封置为 RESEALED。
     */
    public int markResealed(long sealId, Instant resealedAt) {
        return jdbc.update("UPDATE valve_seal SET status = 'RESEALED', resealed_at = ?"
                + " WHERE id = ? AND status = 'ACTIVE'", Timestamp.from(resealedAt), sealId);
    }

    /**
     * 铅封号是否已存在（唯一约束的预检，最终仍由唯一约束兜底）。
     */
    public boolean existsByCode(String sealCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM valve_seal WHERE seal_code = ?", Integer.class, sealCode);
        return count != null && count > 0;
    }
}
