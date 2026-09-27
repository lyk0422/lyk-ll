package com.example.starter.repo;

import com.example.starter.domain.BindingStatus;
import com.example.starter.domain.CustomsBinding;
import com.example.starter.domain.CustomsDeclaration;
import com.example.starter.domain.CustomsDeclarationStatus;
import com.example.starter.domain.ReleaseReason;
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

/**
 * 进口通关单与包装子批绑定存取。
 * 数量核销以通关单行锁（SELECT ... FOR UPDATE）串行化，保证预占/核销/撤销竞争唯一。
 */
@Repository
public class CustomsRepository {

    private final JdbcTemplate jdbc;

    public CustomsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CustomsDeclaration> DECLARATION_MAPPER = (rs, rowNum) -> {
        CustomsDeclaration declaration = new CustomsDeclaration();
        declaration.setId(rs.getLong("id"));
        declaration.setDeclarationNo(rs.getString("declaration_no"));
        declaration.setEnterpriseName(rs.getString("enterprise_name"));
        declaration.setProductName(rs.getString("product_name"));
        declaration.setValidUntil(rs.getTimestamp("valid_until").toInstant());
        declaration.setTotalDoses(rs.getInt("total_doses"));
        declaration.setReservedDoses(rs.getInt("reserved_doses"));
        declaration.setConsumedDoses(rs.getInt("consumed_doses"));
        declaration.setStatus(CustomsDeclarationStatus.valueOf(rs.getString("status")));
        declaration.setVersion(rs.getInt("version"));
        declaration.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        declaration.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return declaration;
    };

    private static final RowMapper<CustomsBinding> BINDING_MAPPER = (rs, rowNum) -> {
        CustomsBinding binding = new CustomsBinding();
        binding.setId(rs.getLong("id"));
        binding.setDeclarationId(rs.getLong("declaration_id"));
        binding.setReviewId(rs.getLong("review_id"));
        binding.setDeclaredDoses(rs.getInt("declared_doses"));
        binding.setStatus(BindingStatus.valueOf(rs.getString("status")));
        String reason = rs.getString("release_reason");
        binding.setReleaseReason(reason == null ? null : ReleaseReason.valueOf(reason));
        binding.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        binding.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        Timestamp consumed = rs.getTimestamp("consumed_at");
        binding.setConsumedAt(consumed == null ? null : consumed.toInstant());
        Timestamp released = rs.getTimestamp("released_at");
        binding.setReleasedAt(released == null ? null : released.toInstant());
        return binding;
    };

    /** 登记通关单（版本从 1 开始）。单号唯一冲突时返回 null。 */
    public Long insertDeclaration(CustomsDeclaration declaration) {
        String sql = """
                INSERT INTO customs_declaration
                    (declaration_no, enterprise_name, product_name, valid_until,
                     total_doses, reserved_doses, consumed_doses, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, 0, ?, 1, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, declaration.getDeclarationNo());
                ps.setString(2, declaration.getEnterpriseName());
                ps.setString(3, declaration.getProductName());
                ps.setTimestamp(4, Timestamp.from(declaration.getValidUntil()));
                ps.setInt(5, declaration.getTotalDoses());
                ps.setString(6, declaration.getStatus().name());
                ps.setTimestamp(7, Timestamp.from(declaration.getCreatedAt()));
                ps.setTimestamp(8, Timestamp.from(declaration.getUpdatedAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            return null;
        }
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /** 按单号查询（不加锁），不存在返回 null。 */
    public CustomsDeclaration findDeclaration(String declarationNo) {
        return jdbc.query("SELECT * FROM customs_declaration WHERE declaration_no = ?",
                DECLARATION_MAPPER, declarationNo).stream().findFirst().orElse(null);
    }

    /** 按单号加行锁查询，不存在返回 null。须在事务内调用。 */
    public CustomsDeclaration lockDeclaration(String declarationNo) {
        return jdbc.query("SELECT * FROM customs_declaration WHERE declaration_no = ? FOR UPDATE",
                DECLARATION_MAPPER, declarationNo).stream().findFirst().orElse(null);
    }

    /** 按主键加行锁查询，不存在返回 null。须在事务内调用。 */
    public CustomsDeclaration lockDeclarationById(Long id) {
        return jdbc.query("SELECT * FROM customs_declaration WHERE id = ? FOR UPDATE",
                DECLARATION_MAPPER, id).stream().findFirst().orElse(null);
    }

    /** 更新通关单数量、状态与版本（调用方负责 version+1）。 */
    public void updateDeclaration(CustomsDeclaration declaration) {
        jdbc.update("""
                        UPDATE customs_declaration
                        SET total_doses = ?, reserved_doses = ?, consumed_doses = ?,
                            status = ?, version = ?, updated_at = ?
                        WHERE id = ?
                        """,
                declaration.getTotalDoses(),
                declaration.getReservedDoses(),
                declaration.getConsumedDoses(),
                declaration.getStatus().name(),
                declaration.getVersion(),
                Timestamp.from(declaration.getUpdatedAt()),
                declaration.getId());
    }

    /** 插入子批绑定。子批唯一约束冲突时返回 null（一个子批只能绑定一张单）。 */
    public Long insertBinding(CustomsBinding binding) {
        String sql = """
                INSERT INTO customs_binding
                    (declaration_id, review_id, declared_doses, status, release_reason,
                     created_at, updated_at, consumed_at, released_at)
                VALUES (?, ?, ?, ?, NULL, ?, ?, NULL, NULL)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, binding.getDeclarationId());
                ps.setLong(2, binding.getReviewId());
                ps.setInt(3, binding.getDeclaredDoses());
                ps.setString(4, binding.getStatus().name());
                ps.setTimestamp(5, Timestamp.from(binding.getCreatedAt()));
                ps.setTimestamp(6, Timestamp.from(binding.getUpdatedAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            return null;
        }
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /** 按审查流程 ID 查询绑定（不加锁），不存在返回 null。 */
    public CustomsBinding findBindingByReviewId(Long reviewId) {
        return jdbc.query("SELECT * FROM customs_binding WHERE review_id = ?",
                BINDING_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 按审查流程 ID 加行锁查询绑定，不存在返回 null。须在事务内调用。 */
    public CustomsBinding lockBindingByReviewId(Long reviewId) {
        return jdbc.query("SELECT * FROM customs_binding WHERE review_id = ? FOR UPDATE",
                BINDING_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 读取通关单下全部绑定明细（按主键排序，台账可复算）。 */
    public List<CustomsBinding> listBindings(Long declarationId) {
        return jdbc.query("SELECT * FROM customs_binding WHERE declaration_id = ? ORDER BY id",
                BINDING_MAPPER, declarationId);
    }

    /** 更新绑定状态与核销/释放时刻。 */
    public void updateBinding(CustomsBinding binding) {
        jdbc.update("""
                        UPDATE customs_binding
                        SET declaration_id = ?, declared_doses = ?, status = ?, release_reason = ?,
                            updated_at = ?, consumed_at = ?, released_at = ?
                        WHERE id = ?
                        """,
                binding.getDeclarationId(),
                binding.getDeclaredDoses(),
                binding.getStatus().name(),
                binding.getReleaseReason() == null ? null : binding.getReleaseReason().name(),
                Timestamp.from(binding.getUpdatedAt()),
                toTimestamp(binding.getConsumedAt()),
                toTimestamp(binding.getReleasedAt()),
                binding.getId());
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
