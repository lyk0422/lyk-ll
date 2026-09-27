package com.example.starter.repo;

import com.example.starter.domain.CustomsDeclaration;
import com.example.starter.domain.CustomsReservation;
import com.example.starter.domain.CustomsRiskEvent;
import com.example.starter.domain.ReservationStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 进口通关单、包装子批预占绑定与风险事件存取。
 * 绑定/签发/撤销经 SELECT ... FOR UPDATE 行锁串行化，乐观锁版本保证提交顺序唯一。
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
        declaration.setValidUntil(rs.getDate("valid_until").toLocalDate());
        declaration.setTotalQuantity(rs.getInt("total_quantity"));
        declaration.setVersion(rs.getInt("version"));
        declaration.setRevoked(rs.getBoolean("revoked"));
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        declaration.setRevokedAt(revokedAt == null ? null : revokedAt.toInstant());
        declaration.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        declaration.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return declaration;
    };

    private static final RowMapper<CustomsReservation> RESERVATION_MAPPER = (rs, rowNum) -> {
        CustomsReservation reservation = new CustomsReservation();
        reservation.setId(rs.getLong("id"));
        reservation.setCustomsId(rs.getLong("customs_id"));
        reservation.setReviewId(rs.getLong("review_id"));
        reservation.setDeclaredQuantity(rs.getInt("declared_quantity"));
        reservation.setStatus(ReservationStatus.valueOf(rs.getString("status")));
        reservation.setVersion(rs.getInt("version"));
        reservation.setReservedAt(rs.getTimestamp("reserved_at").toInstant());
        Timestamp releasedAt = rs.getTimestamp("released_at");
        reservation.setReleasedAt(releasedAt == null ? null : releasedAt.toInstant());
        Timestamp writtenOffAt = rs.getTimestamp("written_off_at");
        reservation.setWrittenOffAt(writtenOffAt == null ? null : writtenOffAt.toInstant());
        reservation.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        reservation.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return reservation;
    };

    // ============================ 通关单 ============================

    /** 登记通关单。通关单号唯一冲突时返回 null（并发登记唯一受理落败方）。 */
    public Long insertDeclaration(CustomsDeclaration declaration) {
        String sql = """
                INSERT INTO customs_declaration
                    (declaration_no, enterprise_name, product_name, valid_until, total_quantity,
                     version, revoked, revoked_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, declaration.getDeclarationNo());
                ps.setString(2, declaration.getEnterpriseName());
                ps.setString(3, declaration.getProductName());
                ps.setDate(4, Date.valueOf(declaration.getValidUntil()));
                ps.setInt(5, declaration.getTotalQuantity());
                ps.setInt(6, declaration.getVersion());
                ps.setBoolean(7, declaration.isRevoked());
                ps.setTimestamp(8, toTimestamp(declaration.getRevokedAt()));
                ps.setTimestamp(9, Timestamp.from(declaration.getCreatedAt()));
                ps.setTimestamp(10, Timestamp.from(declaration.getUpdatedAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            return null;
        }
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /** 按通关单号查询（不加锁）。 */
    public CustomsDeclaration findDeclaration(String declarationNo) {
        return jdbc.query("SELECT * FROM customs_declaration WHERE declaration_no = ?",
                DECLARATION_MAPPER, declarationNo).stream().findFirst().orElse(null);
    }

    /** 按主键查询（不加锁），不存在返回 null。 */
    public CustomsDeclaration findDeclarationById(Long id) {
        return jdbc.query("SELECT * FROM customs_declaration WHERE id = ?",
                DECLARATION_MAPPER, id).stream().findFirst().orElse(null);
    }

    /** 按通关单号加行锁查询，不存在返回 null。须在事务内调用。 */
    public CustomsDeclaration lockDeclaration(String declarationNo) {
        return jdbc.query("SELECT * FROM customs_declaration WHERE declaration_no = ? FOR UPDATE",
                DECLARATION_MAPPER, declarationNo).stream().findFirst().orElse(null);
    }

    /** 按主键加行锁查询，不存在返回 null。须在事务内调用。 */
    public CustomsDeclaration lockDeclarationById(Long id) {
        return jdbc.query("SELECT * FROM customs_declaration WHERE id = ? FOR UPDATE",
                DECLARATION_MAPPER, id).stream().findFirst().orElse(null);
    }

    /**
     * 乐观锁更新通关单（总数、撤销标记、版本 +1）。
     * 仅当数据库当前版本等于期望版本时生效，返回是否更新成功。
     */
    public boolean updateDeclarationVersioned(CustomsDeclaration declaration, int expectedVersion) {
        int updated = jdbc.update("""
                        UPDATE customs_declaration
                        SET total_quantity = ?, revoked = ?, revoked_at = ?, version = ?, updated_at = ?
                        WHERE id = ? AND version = ?
                        """,
                declaration.getTotalQuantity(),
                declaration.isRevoked(),
                toTimestamp(declaration.getRevokedAt()),
                declaration.getVersion(),
                Timestamp.from(declaration.getUpdatedAt()),
                declaration.getId(),
                expectedVersion);
        return updated == 1;
    }

    // ============================ 预占绑定 ============================

    /** 插入预占绑定。子批唯一约束冲突时返回 null（每个子批只能绑定一张通关单）。 */
    public Long insertReservation(CustomsReservation reservation) {
        String sql = """
                INSERT INTO customs_reservation
                    (customs_id, review_id, declared_quantity, status, version,
                     reserved_at, released_at, written_off_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, reservation.getCustomsId());
                ps.setLong(2, reservation.getReviewId());
                ps.setInt(3, reservation.getDeclaredQuantity());
                ps.setString(4, reservation.getStatus().name());
                ps.setInt(5, reservation.getVersion());
                ps.setTimestamp(6, Timestamp.from(reservation.getReservedAt()));
                ps.setTimestamp(7, toTimestamp(reservation.getReleasedAt()));
                ps.setTimestamp(8, toTimestamp(reservation.getWrittenOffAt()));
                ps.setTimestamp(9, Timestamp.from(reservation.getCreatedAt()));
                ps.setTimestamp(10, Timestamp.from(reservation.getUpdatedAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            return null;
        }
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /** 按审查流程 ID 查询绑定（不加锁），不存在返回 null。 */
    public CustomsReservation findReservationByReview(Long reviewId) {
        return jdbc.query("SELECT * FROM customs_reservation WHERE review_id = ?",
                RESERVATION_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 按审查流程 ID 加行锁查询绑定，不存在返回 null。须在事务内调用。 */
    public CustomsReservation lockReservationByReview(Long reviewId) {
        return jdbc.query("SELECT * FROM customs_reservation WHERE review_id = ? FOR UPDATE",
                RESERVATION_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 查询通关单下全部绑定（台账用，不加锁）。 */
    public List<CustomsReservation> listReservations(Long customsId) {
        return jdbc.query("SELECT * FROM customs_reservation WHERE customs_id = ? ORDER BY id",
                RESERVATION_MAPPER, customsId);
    }

    /** 加行锁查询通关单下全部绑定（撤销释放用）。须在事务内调用。 */
    public List<CustomsReservation> lockReservations(Long customsId) {
        return jdbc.query("SELECT * FROM customs_reservation WHERE customs_id = ? ORDER BY id FOR UPDATE",
                RESERVATION_MAPPER, customsId);
    }

    /**
     * 乐观锁更新预占绑定（状态、释放/核销时刻、版本 +1）。
     * 仅当数据库当前版本等于期望版本时生效，返回是否更新成功。
     */
    public boolean updateReservationVersioned(CustomsReservation reservation, int expectedVersion) {
        int updated = jdbc.update("""
                        UPDATE customs_reservation
                        SET status = ?, released_at = ?, written_off_at = ?, version = ?, updated_at = ?
                        WHERE id = ? AND version = ?
                        """,
                reservation.getStatus().name(),
                toTimestamp(reservation.getReleasedAt()),
                toTimestamp(reservation.getWrittenOffAt()),
                reservation.getVersion(),
                Timestamp.from(reservation.getUpdatedAt()),
                reservation.getId(),
                expectedVersion);
        return updated == 1;
    }

    /** 统计通关单当前预占中数量（单位：支）。 */
    public int sumReservedQuantity(Long customsId) {
        Integer sum = jdbc.queryForObject("""
                        SELECT COALESCE(SUM(declared_quantity), 0) FROM customs_reservation
                        WHERE customs_id = ? AND status = 'RESERVED'
                        """,
                Integer.class, customsId);
        return sum == null ? 0 : sum;
    }

    /** 统计通关单已核销数量（单位：支）。 */
    public int sumWrittenOffQuantity(Long customsId) {
        Integer sum = jdbc.queryForObject("""
                        SELECT COALESCE(SUM(declared_quantity), 0) FROM customs_reservation
                        WHERE customs_id = ? AND status = 'WRITTEN_OFF'
                        """,
                Integer.class, customsId);
        return sum == null ? 0 : sum;
    }

    // ============================ 风险事件 ============================

    /** 登记通关单风险事件（证据不可变）。 */
    public void insertRiskEvent(CustomsRiskEvent event) {
        Instant now = event.getEventAt() != null ? event.getEventAt() : Instant.now();
        jdbc.update("""
                        INSERT INTO customs_risk_event (customs_id, review_id, event_type, payload, event_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                event.getCustomsId(),
                event.getReviewId(),
                event.getEventType(),
                event.getPayload(),
                Timestamp.from(now),
                Timestamp.from(Instant.now()));
    }

    /** 读取通关单风险事件流水（按发生顺序）。 */
    public List<CustomsRiskEvent> listRiskEvents(Long customsId) {
        return jdbc.query("SELECT * FROM customs_risk_event WHERE customs_id = ? ORDER BY id",
                (rs, rowNum) -> {
                    CustomsRiskEvent event = new CustomsRiskEvent();
                    event.setId(rs.getLong("id"));
                    event.setCustomsId(rs.getLong("customs_id"));
                    long reviewId = rs.getLong("review_id");
                    event.setReviewId(rs.wasNull() ? null : reviewId);
                    event.setEventType(rs.getString("event_type"));
                    event.setPayload(rs.getString("payload"));
                    event.setEventAt(rs.getTimestamp("event_at").toInstant());
                    event.setCreatedAt(rs.getTimestamp("created_at").toInstant());
                    return event;
                }, customsId);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
