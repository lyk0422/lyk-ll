package com.example.starter.repo;

import com.example.starter.domain.BindingStatus;
import com.example.starter.domain.CustomsDeclaration;
import com.example.starter.domain.CustomsEvent;
import com.example.starter.domain.SubBatchBinding;
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
 * 进口通关单、子批绑定与通关事件存取。
 * 并发变更使用 SELECT ... FOR UPDATE 行锁，保证预占/核销/撤销的唯一终态。
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
        declaration.setTotalQuantity(rs.getInt("total_quantity"));
        declaration.setReservedQuantity(rs.getInt("reserved_quantity"));
        declaration.setConsumedQuantity(rs.getInt("consumed_quantity"));
        declaration.setRevoked(rs.getBoolean("revoked"));
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        declaration.setRevokedAt(revokedAt == null ? null : revokedAt.toInstant());
        declaration.setVersion(rs.getInt("version"));
        declaration.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        declaration.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return declaration;
    };

    private static final RowMapper<SubBatchBinding> BINDING_MAPPER = (rs, rowNum) -> {
        SubBatchBinding binding = new SubBatchBinding();
        binding.setId(rs.getLong("id"));
        binding.setDeclarationId(rs.getLong("declaration_id"));
        binding.setReviewId(rs.getLong("review_id"));
        binding.setBatchNo(rs.getString("batch_no"));
        binding.setDeclaredQuantity(rs.getInt("declared_quantity"));
        binding.setStatus(BindingStatus.valueOf(rs.getString("status")));
        binding.setBoundAt(rs.getTimestamp("bound_at").toInstant());
        Timestamp consumedAt = rs.getTimestamp("consumed_at");
        binding.setConsumedAt(consumedAt == null ? null : consumedAt.toInstant());
        Timestamp releasedAt = rs.getTimestamp("released_at");
        binding.setReleasedAt(releasedAt == null ? null : releasedAt.toInstant());
        binding.setReleaseReason(rs.getString("release_reason"));
        binding.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        binding.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return binding;
    };

    /**
     * 登记新通关单。单号唯一冲突时返回 null（并发登记唯一受理落败方）。
     */
    public Long insertDeclaration(CustomsDeclaration declaration) {
        String sql = """
                INSERT INTO customs_declaration
                    (declaration_no, enterprise_name, product_name, valid_until,
                     total_quantity, reserved_quantity, consumed_quantity,
                     revoked, revoked_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, declaration.getDeclarationNo());
                ps.setString(2, declaration.getEnterpriseName());
                ps.setString(3, declaration.getProductName());
                ps.setTimestamp(4, Timestamp.from(declaration.getValidUntil()));
                ps.setInt(5, declaration.getTotalQuantity());
                ps.setInt(6, declaration.getReservedQuantity());
                ps.setInt(7, declaration.getConsumedQuantity());
                ps.setBoolean(8, declaration.isRevoked());
                ps.setTimestamp(9, toTimestamp(declaration.getRevokedAt()));
                ps.setInt(10, declaration.getVersion());
                ps.setTimestamp(11, Timestamp.from(declaration.getCreatedAt()));
                ps.setTimestamp(12, Timestamp.from(declaration.getUpdatedAt()));
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

    /** 更新台账数量、撤销标记与版本（调用方负责在事务内先加行锁）。 */
    public void updateDeclaration(CustomsDeclaration declaration) {
        jdbc.update("""
                        UPDATE customs_declaration
                        SET total_quantity = ?, reserved_quantity = ?, consumed_quantity = ?,
                            revoked = ?, revoked_at = ?, version = ?, updated_at = ?
                        WHERE id = ?
                        """,
                declaration.getTotalQuantity(),
                declaration.getReservedQuantity(),
                declaration.getConsumedQuantity(),
                declaration.isRevoked(),
                toTimestamp(declaration.getRevokedAt()),
                declaration.getVersion(),
                Timestamp.from(declaration.getUpdatedAt()),
                declaration.getId());
    }

    /**
     * 插入子批绑定。同一子批重复绑定（uk_binding_review）时返回 null。
     */
    public Long insertBinding(SubBatchBinding binding) {
        String sql = """
                INSERT INTO sub_batch_binding
                    (declaration_id, review_id, batch_no, declared_quantity, status,
                     bound_at, consumed_at, released_at, release_reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, binding.getDeclarationId());
                ps.setLong(2, binding.getReviewId());
                ps.setString(3, binding.getBatchNo());
                ps.setInt(4, binding.getDeclaredQuantity());
                ps.setString(5, binding.getStatus().name());
                ps.setTimestamp(6, Timestamp.from(binding.getBoundAt()));
                ps.setTimestamp(7, toTimestamp(binding.getConsumedAt()));
                ps.setTimestamp(8, toTimestamp(binding.getReleasedAt()));
                ps.setString(9, binding.getReleaseReason());
                ps.setTimestamp(10, Timestamp.from(binding.getCreatedAt()));
                ps.setTimestamp(11, Timestamp.from(binding.getUpdatedAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            return null;
        }
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /** 按审查流程查询绑定（不加锁），不存在返回 null。 */
    public SubBatchBinding findBindingByReviewId(Long reviewId) {
        return jdbc.query("SELECT * FROM sub_batch_binding WHERE review_id = ?",
                BINDING_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 查询通关单下全部绑定（按主键排序，台账可复算）。 */
    public List<SubBatchBinding> listBindings(Long declarationId) {
        return jdbc.query("SELECT * FROM sub_batch_binding WHERE declaration_id = ? ORDER BY id",
                BINDING_MAPPER, declarationId);
    }

    /** 查询通关单下指定状态的绑定（不加锁，须在已锁通关单的事务内使用）。 */
    public List<SubBatchBinding> listBindingsByStatus(Long declarationId, BindingStatus status) {
        return jdbc.query("SELECT * FROM sub_batch_binding WHERE declaration_id = ? AND status = ? ORDER BY id",
                BINDING_MAPPER, declarationId, status.name());
    }

    /** 更新绑定状态与核销/释放时刻（调用方负责在事务内先加锁）。 */
    public void updateBinding(SubBatchBinding binding) {
        jdbc.update("""
                        UPDATE sub_batch_binding
                        SET status = ?, consumed_at = ?, released_at = ?, release_reason = ?, updated_at = ?
                        WHERE id = ?
                        """,
                binding.getStatus().name(),
                toTimestamp(binding.getConsumedAt()),
                toTimestamp(binding.getReleasedAt()),
                binding.getReleaseReason(),
                Timestamp.from(binding.getUpdatedAt()),
                binding.getId());
    }

    /** 登记通关单事件。 */
    public void insertEvent(CustomsEvent event) {
        Instant now = event.getEventAt() != null ? event.getEventAt() : Instant.now();
        jdbc.update("""
                        INSERT INTO customs_event (declaration_id, event_type, payload, event_at, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                event.getDeclarationId(),
                event.getEventType(),
                event.getPayload(),
                Timestamp.from(now),
                Timestamp.from(Instant.now()));
    }

    /** 读取通关单事件流水（按发生顺序）。 */
    public List<CustomsEvent> listEvents(Long declarationId) {
        return jdbc.query("SELECT * FROM customs_event WHERE declaration_id = ? ORDER BY id",
                (rs, rowNum) -> {
                    CustomsEvent event = new CustomsEvent();
                    event.setId(rs.getLong("id"));
                    event.setDeclarationId(rs.getLong("declaration_id"));
                    event.setEventType(rs.getString("event_type"));
                    event.setPayload(rs.getString("payload"));
                    event.setEventAt(rs.getTimestamp("event_at").toInstant());
                    event.setCreatedAt(rs.getTimestamp("created_at").toInstant());
                    return event;
                }, declarationId);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
