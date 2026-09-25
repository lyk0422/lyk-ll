package com.example.starter.repo;

import com.example.starter.domain.EnterpriseProductStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 企业-品种受理状态存取（连续不合格计数、暂停受理）。
 */
@Repository
public class EnterpriseProductStatusRepository {

    private static final RowMapper<EnterpriseProductStatus> MAPPER = (rs, rowNum) -> {
        EnterpriseProductStatus status = new EnterpriseProductStatus();
        status.setId(rs.getLong("id"));
        status.setEnterpriseName(rs.getString("enterprise_name"));
        status.setProductName(rs.getString("product_name"));
        status.setConsecutiveFailed(rs.getInt("consecutive_failed"));
        status.setSuspended(rs.getBoolean("suspended"));
        Timestamp suspendedAt = rs.getTimestamp("suspended_at");
        status.setSuspendedAt(suspendedAt == null ? null : suspendedAt.toInstant());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        status.setUpdatedAt(updatedAt == null ? null : updatedAt.toInstant());
        return status;
    };

    private final JdbcTemplate jdbc;

    public EnterpriseProductStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询企业-品种状态，不存在返回 null。 */
    public EnterpriseProductStatus find(String enterprise, String product) {
        return jdbc.query("""
                        SELECT * FROM enterprise_product_status
                        WHERE enterprise_name = ? AND product_name = ?
                        """,
                MAPPER, enterprise, product).stream().findFirst().orElse(null);
    }

    /** 不存在则插入初始状态行（计数 0，未暂停）；并发首插落败静默忽略。 */
    public void initIfAbsent(String enterprise, String product, Instant now) {
        try {
            jdbc.update("""
                            INSERT INTO enterprise_product_status
                                (enterprise_name, product_name, consecutive_failed, suspended, suspended_at, updated_at)
                            VALUES (?, ?, 0, FALSE, NULL, ?)
                            """,
                    enterprise, product, Timestamp.from(now));
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // 并发申报同企业同品种时另一事务已初始化，直接复用。
        }
    }

    /**
     * 评定不合格后累加计数；恰累加到 3 时置暂停标记。
     *
     * @return 更新后的状态行
     */
    public EnterpriseProductStatus incrementFailed(String enterprise, String product, Instant now) {
        jdbc.update("""
                        UPDATE enterprise_product_status
                        SET consecutive_failed = consecutive_failed + 1,
                            suspended = CASE WHEN consecutive_failed + 1 >= 3 THEN TRUE ELSE suspended END,
                            suspended_at = CASE WHEN consecutive_failed + 1 >= 3 THEN ? ELSE suspended_at END,
                            updated_at = ?
                        WHERE enterprise_name = ? AND product_name = ?
                        """,
                Timestamp.from(now), Timestamp.from(now), enterprise, product);
        return find(enterprise, product);
    }

    /** 评定合格后清零连续不合格计数（暂停标记一旦触发不由此路径解除）。 */
    public void resetFailed(String enterprise, String product, Instant now) {
        jdbc.update("""
                        UPDATE enterprise_product_status
                        SET consecutive_failed = 0, updated_at = ?
                        WHERE enterprise_name = ? AND product_name = ?
                        """,
                Timestamp.from(now), enterprise, product);
    }
}
