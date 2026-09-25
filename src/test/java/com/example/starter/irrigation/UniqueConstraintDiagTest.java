package com.example.starter.irrigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * H2 唯一约束边界测试：生效唯一键强制同轮同组唯一生效版本，
 * 非生效版本（effective_unique_key 为 NULL）允许多行共存。
 */
class UniqueConstraintDiagTest extends AbstractIntegrationTest {

    @Test
    void effectiveUniqueKeyRejectsDuplicate() {
        jdbc.update("INSERT INTO irr_group (group_code, group_name, created_at) VALUES ('GD', '诊断组', CURRENT_TIMESTAMP)");
        jdbc.update("""
                INSERT INTO irr_plan_version
                    (group_code, turn_no, version_no, status, available_cubic_meter,
                     request_id, fingerprint, effective_unique_key, published_at, created_at)
                VALUES ('GD', 1, 1, 'EFFECTIVE', 10, 'r1', 'fp1', '1|GD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO irr_plan_version
                    (group_code, turn_no, version_no, status, available_cubic_meter,
                     request_id, fingerprint, effective_unique_key, published_at, created_at)
                VALUES ('GD', 1, 2, 'EFFECTIVE', 20, 'r2', 'fp2', '1|GD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM irr_plan_version WHERE effective_unique_key = '1|GD'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void multipleNullEffectiveKeysAreAllowed() {
        jdbc.update("INSERT INTO irr_group (group_code, group_name, created_at) VALUES ('GN', '空键组', CURRENT_TIMESTAMP)");
        jdbc.update("""
                INSERT INTO irr_plan_version
                    (group_code, turn_no, version_no, status, available_cubic_meter,
                     request_id, fingerprint, effective_unique_key, published_at, created_at)
                VALUES ('GN', 1, 1, 'SUPERSEDED', 10, 'a1', 'f1', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO irr_plan_version
                    (group_code, turn_no, version_no, status, available_cubic_meter,
                     request_id, fingerprint, effective_unique_key, published_at, created_at)
                VALUES ('GN', 1, 2, 'SUPERSEDED', 20, 'a2', 'f2', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM irr_plan_version WHERE group_code = 'GN'",
                Integer.class)).isEqualTo(2);
    }
}
