package com.example.rebate.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 真实银行流水全局互斥登记数据访问。
 *
 * <p>单笔追缴回执与汇总分配母流水共享同一命名空间：主键保证同一真实流水只登记一次，
 * 先登记者胜出；并发另一入口在主键上阻塞后读到已提交登记，按用途（SINGLE/MASTER）判定互斥。</p>
 */
@Repository
public class BankSerialRepository {

    /** 旧单笔追缴回执。 */
    public static final String KIND_SINGLE = "SINGLE";
    /** 汇总分配母流水。 */
    public static final String KIND_MASTER = "MASTER";

    private final JdbcTemplate jdbc;

    public BankSerialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试登记流水用途。
     *
     * @return {@code true} 登记成功；{@code false} 该流水已被任一入口登记（已提交）
     */
    public boolean tryRegister(String serial, String kind, Instant now) {
        try {
            jdbc.update("INSERT INTO t_bank_serial (serial, kind, created_at) VALUES (?, ?, ?)",
                    serial, kind, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 查询流水已登记用途；未登记返回空。 */
    public Optional<String> findKind(String serial) {
        List<String> list = jdbc.query("SELECT kind FROM t_bank_serial WHERE serial = ?",
                (rs, rowNum) -> rs.getString(1), serial);
        return list.stream().findFirst();
    }
}
