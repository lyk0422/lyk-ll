package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 真实 H2（MODE=MySQL）数据库约束测试：直接绕过服务层写非法数据，
 * 验证 CHECK 约束与唯一约束确实在数据库边界拦截，不依赖 Java 校验或内存 Map。
 */
class CustomsConstraintTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    private Long insertCustoms(String no, int total, int reserved, int writtenOff) {
        jdbc.update("""
                        INSERT INTO customs_declaration
                            (customs_no, enterprise_name, product_name, valid_until, total_quantity,
                             reserved_quantity, written_off_quantity, status, version, revoked_at,
                             created_at, updated_at)
                        VALUES (?, '厂', '苗', ?, ?, ?, ?, 'ACTIVE', 1, NULL, ?, ?)
                        """,
                no, Timestamp.from(T0.plusSeconds(3600)), total, reserved, writtenOff,
                Timestamp.from(T0), Timestamp.from(T0));
        return jdbc.queryForObject("SELECT id FROM customs_declaration WHERE customs_no = ?",
                Long.class, no);
    }

    @Test
    void nonPositiveTotalIsRejectedByCheckConstraint() {
        assertThrows(DataIntegrityViolationException.class, () ->
                insertCustoms("CI-ZERO", 0, 0, 0));
        assertEquals(0, countCustoms("CI-ZERO"), "CHECK 拒绝后不得落库");
    }

    @Test
    void reservationPlusWrittenOffAboveTotalIsRejectedByCheckConstraint() {
        Long id = insertCustoms("CI-BOUNDS", 100, 60, 40);
        // 恰占满合法
        int updated = jdbc.update(
                "UPDATE customs_declaration SET reserved_quantity = 60, written_off_quantity = 40 WHERE id = ?",
                id);
        assertEquals(1, updated, "恰占满 60+40=100 必须被数据库接受");

        // 超占 101 必须被 ck_customs_quantity_bounds 拒绝
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE customs_declaration SET reserved_quantity = 61 WHERE id = ?", id));
        // 负预占也必须被拒绝
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE customs_declaration SET reserved_quantity = -1 WHERE id = ?", id));
    }

    @Test
    void duplicateBindingForSameReviewIsRejectedByUniqueConstraint() {
        clock.setFixed(T0);
        prepareIssueableImportBatch("con-d", "B-CON-DB");
        Long reviewId = jdbc.queryForObject(
                "SELECT id FROM batch_review WHERE batch_no = 'B-CON-DB'", Long.class);
        Long c1 = insertCustoms("CI-DUP-BIND-1", 100, 0, 0);
        Long c2 = insertCustoms("CI-DUP-BIND-2", 100, 0, 0);

        jdbc.update("""
                        INSERT INTO subbatch_binding
                            (review_id, customs_declaration_id, batch_no, declared_quantity, status,
                             version, reserved_at, created_at, updated_at)
                        VALUES (?, ?, 'B-CON-DB', 10, 'RESERVED', 1, ?, ?, ?)
                        """,
                reviewId, c1, Timestamp.from(T0), Timestamp.from(T0), Timestamp.from(T0));

        // 同一子批再绑第二张单：唯一约束拦截
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("""
                                INSERT INTO subbatch_binding
                                    (review_id, customs_declaration_id, batch_no, declared_quantity, status,
                                     version, reserved_at, created_at, updated_at)
                                VALUES (?, ?, 'B-CON-DB', 10, 'RESERVED', 1, ?, ?, ?)
                                """,
                        reviewId, c2, Timestamp.from(T0), Timestamp.from(T0), Timestamp.from(T0)));
        assertEquals(1, countBindingsForReview(reviewId), "一子批只能绑定一张单");
    }

    @Test
    void nonPositiveBindingQuantityIsRejectedByCheckConstraint() {
        clock.setFixed(T0);
        prepareIssueableImportBatch("con-z", "B-CON-Z");
        Long reviewId = jdbc.queryForObject(
                "SELECT id FROM batch_review WHERE batch_no = 'B-CON-Z'", Long.class);
        Long customsId = insertCustoms("CI-ZERO-BIND", 100, 0, 0);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                        INSERT INTO subbatch_binding
                            (review_id, customs_declaration_id, batch_no, declared_quantity, status,
                             version, reserved_at, created_at, updated_at)
                        VALUES (?, ?, 'B-CON-Z', 0, 'RESERVED', 1, ?, ?, ?)
                        """,
                reviewId, customsId, Timestamp.from(T0), Timestamp.from(T0), Timestamp.from(T0)));
        assertEquals(0, countBindingsForReview(reviewId));
    }

    private int countCustoms(String no) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customs_declaration WHERE customs_no = ?", Integer.class, no);
        return c == null ? 0 : c;
    }

    private int countBindingsForReview(Long reviewId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM subbatch_binding WHERE review_id = ?", Integer.class, reviewId);
        return c == null ? 0 : c;
    }
}
