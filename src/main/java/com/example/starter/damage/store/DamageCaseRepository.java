package com.example.starter.damage.store;

import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.dto.Views.CaseView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 残损案主表数据访问。进行中案件通过 active_key 唯一索引保证同一箱号仅一案。
 */
@Repository
public class DamageCaseRepository {

    private static final RowMapper<CaseRow> ROW_MAPPER = (rs, n) -> new CaseRow(
            rs.getLong("id"),
            rs.getString("container_no"),
            rs.getBigDecimal("container_value"),
            CaseStatus.valueOf(rs.getString("status")),
            Optional.ofNullable(rs.getString("determination")).map(Determination::valueOf).orElse(null),
            rs.getInt("rework_count"),
            toLocalDateTime(rs.getTimestamp("closed_at")));

    private final JdbcTemplate jdbc;

    public DamageCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 案件行记录，closedAt 为 null 表示未结案 */
    public record CaseRow(
            long id,
            String containerNo,
            BigDecimal containerValue,
            CaseStatus status,
            Determination determination,
            int reworkCount,
            LocalDateTime closedAt) {

        public CaseView toView() {
            return new CaseView(id, containerNo, containerValue, status, determination, reworkCount, closedAt);
        }
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /**
     * 登记新案件：状态 REGISTERED，active_key=箱号；同箱号进行中案件冲突时抛出 DuplicateKeyException。
     *
     * @return 新案件 ID
     */
    public long insert(String containerNo, BigDecimal containerValue, LocalDateTime now) {
        jdbc.update("insert into damage_case(container_no, container_value, status, rework_count, active_key, created_at, updated_at)"
                        + " values (?,?,?,?,?,?,?)",
                containerNo, containerValue, CaseStatus.REGISTERED.name(), 0, containerNo, now, now);
        Long id = jdbc.queryForObject("select max(id) from damage_case where container_no = ?", Long.class, containerNo);
        return id;
    }

    /** 按 ID 查询案件 */
    public Optional<CaseRow> findById(long id) {
        return jdbc.query("select * from damage_case where id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /** 按 ID 加行锁查询案件（同事务内串行化状态流转） */
    public Optional<CaseRow> findByIdForUpdate(long id) {
        return jdbc.query("select * from damage_case where id = ? for update", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /** 更新状态与判定 */
    public void updateState(long id, CaseStatus status, Determination determination, LocalDateTime now) {
        jdbc.update("update damage_case set status = ?, determination = ?, updated_at = ? where id = ?",
                status.name(), determination == null ? null : determination.name(), now, id);
    }

    /** 更新状态、判定与返修次数 */
    public void updateState(long id, CaseStatus status, Determination determination, int reworkCount, LocalDateTime now) {
        jdbc.update("update damage_case set status = ?, determination = ?, rework_count = ?, updated_at = ? where id = ?",
                status.name(), determination == null ? null : determination.name(), reworkCount, now, id);
    }

    /**
     * 结案：写入终态、结案时间并释放 active_key（与放行同事务）。
     */
    public void close(long id, CaseStatus terminal, LocalDateTime now) {
        jdbc.update("update damage_case set status = ?, active_key = null, closed_at = ?, updated_at = ? where id = ?",
                terminal.name(), now, now, id);
    }
}
