package com.example.starter.irrigation.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 轮灌组、斗口及组成员关系仓储。
 */
@Repository
public class GroupRepository {

    /** 组斗口成员行（面积单位平方米）。 */
    public record MemberRow(String groupCode, String outletCode, String outletName, long areaSquareMeter) {
    }

    private final JdbcTemplate jdbc;

    public GroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按编号查组成员（含斗口名称），无成员返回空列表。 */
    public List<MemberRow> findMembers(String groupCode) {
        return jdbc.query("""
                        SELECT go.group_code, go.outlet_code, o.outlet_name, go.area_square_meter
                        FROM irr_group_outlet go
                        JOIN irr_outlet o ON o.outlet_code = go.outlet_code
                        WHERE go.group_code = ?
                        ORDER BY go.outlet_code
                        """,
                MEMBER_MAPPER, groupCode);
    }

    /** 组是否存在。 */
    public boolean existsGroup(String groupCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM irr_group WHERE group_code = ?", Integer.class, groupCode);
        return count != null && count > 0;
    }

    /** 查组名，组不存在时返回空。 */
    public java.util.Optional<String> findGroupName(String groupCode) {
        try {
            return java.util.Optional.ofNullable(jdbc.queryForObject(
                    "SELECT group_name FROM irr_group WHERE group_code = ?", String.class, groupCode));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return java.util.Optional.empty();
        }
    }

    /** 建档：组、斗口与成员面积在同一事务内写入。 */
    public void createGroup(String groupCode, String groupName,
                            List<MemberRow> members, Instant now) {
        jdbc.update("INSERT INTO irr_group (group_code, group_name, created_at) VALUES (?, ?, ?)",
                groupCode, groupName, now);
        for (MemberRow member : members) {
            jdbc.update("""
                            INSERT INTO irr_outlet (outlet_code, outlet_name, created_at)
                            VALUES (?, ?, ?)
                            """,
                    member.outletCode(), member.outletName(), now);
            jdbc.update("""
                            INSERT INTO irr_group_outlet
                                (group_code, outlet_code, area_square_meter, created_at)
                            VALUES (?, ?, ?, ?)
                            """,
                    groupCode, member.outletCode(), member.areaSquareMeter(), now);
        }
    }

    /** 组成员行映射。 */
    private static final RowMapper<MemberRow> MEMBER_MAPPER = (ResultSet rs, int rowNum) -> new MemberRow(
            rs.getString("group_code"),
            rs.getString("outlet_code"),
            rs.getString("outlet_name"),
            rs.getLong("area_square_meter"));
}
