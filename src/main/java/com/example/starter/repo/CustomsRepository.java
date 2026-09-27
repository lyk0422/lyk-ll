package com.example.starter.repo;

import com.example.starter.domain.BindingStatus;
import com.example.starter.domain.CustomsDeclaration;
import com.example.starter.domain.CustomsStatus;
import com.example.starter.domain.SubbatchBinding;
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
 * 进口通关单台账与包装子批绑定存取。
 *
 * <p>并发安全统一靠行锁序列化，加锁顺序全局固定为：
 * customs_declaration → subbatch_binding → batch_review，避免死锁；
 * 余量除 Java 复核外由 {@code ck_customs_quantity_bounds} 约束兜底，杜绝超占/超核销。
 */
@Repository
public class CustomsRepository {

    private final JdbcTemplate jdbc;

    public CustomsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CustomsDeclaration> DECLARATION_MAPPER = (rs, rowNum) -> {
        CustomsDeclaration d = new CustomsDeclaration();
        d.setId(rs.getLong("id"));
        d.setCustomsNo(rs.getString("customs_no"));
        d.setEnterpriseName(rs.getString("enterprise_name"));
        d.setProductName(rs.getString("product_name"));
        d.setValidUntil(rs.getTimestamp("valid_until").toInstant());
        d.setTotalQuantity(rs.getInt("total_quantity"));
        d.setReservedQuantity(rs.getInt("reserved_quantity"));
        d.setWrittenOffQuantity(rs.getInt("written_off_quantity"));
        d.setStatus(CustomsStatus.valueOf(rs.getString("status")));
        d.setVersion(rs.getInt("version"));
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        d.setRevokedAt(revokedAt == null ? null : revokedAt.toInstant());
        d.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        d.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return d;
    };

    private static final RowMapper<SubbatchBinding> BINDING_MAPPER = (rs, rowNum) -> {
        SubbatchBinding b = new SubbatchBinding();
        b.setId(rs.getLong("id"));
        b.setReviewId(rs.getLong("review_id"));
        b.setCustomsDeclarationId(rs.getLong("customs_declaration_id"));
        b.setBatchNo(rs.getString("batch_no"));
        b.setDeclaredQuantity(rs.getInt("declared_quantity"));
        b.setStatus(BindingStatus.valueOf(rs.getString("status")));
        b.setVersion(rs.getInt("version"));
        b.setReservedAt(rs.getTimestamp("reserved_at").toInstant());
        Timestamp writtenOffAt = rs.getTimestamp("written_off_at");
        b.setWrittenOffAt(writtenOffAt == null ? null : writtenOffAt.toInstant());
        Timestamp releasedAt = rs.getTimestamp("released_at");
        b.setReleasedAt(releasedAt == null ? null : releasedAt.toInstant());
        b.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        b.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return b;
    };

    /** 登记通关单；通关单号唯一冲突返回 null。 */
    public Long insert(CustomsDeclaration declaration) {
        String sql = """
                INSERT INTO customs_declaration
                    (customs_no, enterprise_name, product_name, valid_until, total_quantity,
                     reserved_quantity, written_off_quantity, status, version, revoked_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, 0, 'ACTIVE', 1, NULL, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, declaration.getCustomsNo());
                ps.setString(2, declaration.getEnterpriseName());
                ps.setString(3, declaration.getProductName());
                ps.setTimestamp(4, Timestamp.from(declaration.getValidUntil()));
                ps.setInt(5, declaration.getTotalQuantity());
                ps.setTimestamp(6, Timestamp.from(declaration.getCreatedAt()));
                ps.setTimestamp(7, Timestamp.from(declaration.getUpdatedAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            return null;
        }
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /** 按通关单号查询（不加锁）。 */
    public CustomsDeclaration findByCustomsNo(String customsNo) {
        return jdbc.query("SELECT * FROM customs_declaration WHERE customs_no = ?",
                DECLARATION_MAPPER, customsNo).stream().findFirst().orElse(null);
    }

    /** 按主键查询（不加锁）。 */
    public CustomsDeclaration findById(Long id) {
        return jdbc.query("SELECT * FROM customs_declaration WHERE id = ?",
                DECLARATION_MAPPER, id).stream().findFirst().orElse(null);
    }

    /** 按通关单号加行锁查询，不存在返回 null。须在事务内调用。 */
    public CustomsDeclaration lockByCustomsNo(String customsNo) {
        return jdbc.query("SELECT * FROM customs_declaration WHERE customs_no = ? FOR UPDATE",
                DECLARATION_MAPPER, customsNo).stream().findFirst().orElse(null);
    }

    /** 按主键加行锁查询，不存在返回 null。须在事务内调用。 */
    public CustomsDeclaration lockById(Long id) {
        return jdbc.query("SELECT * FROM customs_declaration WHERE id = ? FOR UPDATE",
                DECLARATION_MAPPER, id).stream().findFirst().orElse(null);
    }

    /** 全字段更新通关单台账（总量/预占/核销/状态/版本/撤销时刻）。 */
    public void update(CustomsDeclaration d) {
        jdbc.update("""
                        UPDATE customs_declaration
                        SET total_quantity = ?, reserved_quantity = ?, written_off_quantity = ?,
                            status = ?, version = ?, revoked_at = ?, updated_at = ?
                        WHERE id = ?
                        """,
                d.getTotalQuantity(),
                d.getReservedQuantity(),
                d.getWrittenOffQuantity(),
                d.getStatus().name(),
                d.getVersion(),
                toTimestamp(d.getRevokedAt()),
                Timestamp.from(d.getUpdatedAt()),
                d.getId());
    }

    /** 插入子批绑定；同一审查流程重复绑定（uk_binding_review）返回 null。 */
    public Long insertBinding(SubbatchBinding binding) {
        String sql = """
                INSERT INTO subbatch_binding
                    (review_id, customs_declaration_id, batch_no, declared_quantity, status, version,
                     reserved_at, written_off_at, released_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, NULL, NULL, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, binding.getReviewId());
                ps.setLong(2, binding.getCustomsDeclarationId());
                ps.setString(3, binding.getBatchNo());
                ps.setInt(4, binding.getDeclaredQuantity());
                ps.setString(5, binding.getStatus().name());
                ps.setTimestamp(6, Timestamp.from(binding.getReservedAt()));
                ps.setTimestamp(7, Timestamp.from(binding.getCreatedAt()));
                ps.setTimestamp(8, Timestamp.from(binding.getUpdatedAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            return null;
        }
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /** 按审查流程查询绑定（不加锁），不存在返回 null。 */
    public SubbatchBinding findBindingByReviewId(Long reviewId) {
        return jdbc.query("SELECT * FROM subbatch_binding WHERE review_id = ?",
                BINDING_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 按审查流程加行锁查询绑定，不存在返回 null。须在事务内调用。 */
    public SubbatchBinding lockBindingByReviewId(Long reviewId) {
        return jdbc.query("SELECT * FROM subbatch_binding WHERE review_id = ? FOR UPDATE",
                BINDING_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 查询通关单下全部绑定（不加锁，按创建顺序，台账展示用）。 */
    public List<SubbatchBinding> listBindingsByCustomsId(Long customsId) {
        return jdbc.query("SELECT * FROM subbatch_binding WHERE customs_declaration_id = ? ORDER BY id",
                BINDING_MAPPER, customsId);
    }

    /**
     * 加锁锁定通关单下全部处于 RESERVED 的绑定（撤销时批量释放用）。
     * 须在已持有通关单行锁的事务内调用，保持 customs → binding 加锁顺序。
     */
    public List<SubbatchBinding> lockReservedBindings(Long customsId) {
        return jdbc.query(
                "SELECT * FROM subbatch_binding WHERE customs_declaration_id = ? AND status = 'RESERVED' FOR UPDATE",
                BINDING_MAPPER, customsId);
    }

    /** 全字段更新绑定（状态/版本/核销时刻/释放时刻）。 */
    public void updateBinding(SubbatchBinding binding) {
        jdbc.update("""
                        UPDATE subbatch_binding
                        SET status = ?, version = ?, written_off_at = ?, released_at = ?, updated_at = ?
                        WHERE id = ?
                        """,
                binding.getStatus().name(),
                binding.getVersion(),
                toTimestamp(binding.getWrittenOffAt()),
                toTimestamp(binding.getReleasedAt()),
                Timestamp.from(binding.getUpdatedAt()),
                binding.getId());
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
