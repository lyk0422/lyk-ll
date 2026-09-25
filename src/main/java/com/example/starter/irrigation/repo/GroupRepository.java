package com.example.starter.irrigation.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 轮灌组与斗口数据访问。
 */
@Repository
public class GroupRepository {

    /**
     * 轮灌组行。
     */
    public record GroupRow(long id, String code, String name) {
    }

    /**
     * 斗口行。
     */
    public record OutletRow(long id, long groupId, String code, String name, BigDecimal areaMu) {
    }

    private static final RowMapper<GroupRow> GROUP_MAPPER = (rs, n) ->
            new GroupRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"));

    private static final RowMapper<OutletRow> OUTLET_MAPPER = (rs, n) ->
            new OutletRow(rs.getLong("id"), rs.getLong("group_id"), rs.getString("code"),
                    rs.getString("name"), rs.getBigDecimal("area_mu"));

    private final JdbcTemplate jdbc;

    public GroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertGroup(String code, String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rotation_group (code, name) VALUES (?, ?)", new String[]{"id"});
            ps.setString(1, code);
            ps.setString(2, name);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public long insertOutlet(long groupId, String code, String name, BigDecimal areaMu) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO outlet (group_id, code, name, area_mu) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, groupId);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setBigDecimal(4, areaMu);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public Optional<GroupRow> findGroupById(long groupId) {
        List<GroupRow> rows = jdbc.query("SELECT id, code, name FROM rotation_group WHERE id = ?",
                GROUP_MAPPER, groupId);
        return rows.stream().findFirst();
    }

    /**
     * 按斗口编号排序返回组内斗口，保证分配与指纹的确定性。
     */
    public List<OutletRow> findOutletsByGroupId(long groupId) {
        return jdbc.query("SELECT id, group_id, code, name, area_mu FROM outlet "
                + "WHERE group_id = ? ORDER BY code", OUTLET_MAPPER, groupId);
    }

    public Optional<OutletRow> findOutletById(long outletId) {
        List<OutletRow> rows = jdbc.query(
                "SELECT id, group_id, code, name, area_mu FROM outlet WHERE id = ?", OUTLET_MAPPER, outletId);
        return rows.stream().findFirst();
    }
}
