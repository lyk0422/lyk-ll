package com.example.rebate.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 银行流水全局互斥登记数据访问。主键插入即占坑：汇总母流水与旧单笔回执流水共享同一登记表，
 * 同一真实流水只能被其中一种入口占用；并发同流水在主键上串行，后到者占位失败。
 */
@Repository
public class BankSerialRegistryRepository {

    private final JdbcTemplate jdbc;

    public BankSerialRegistryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试占用流水号。
     *
     * @param source 占用入口：SINGLE 旧单笔回执 / AGGREGATE 汇总母凭证
     * @return {@code true} 占用成功；{@code false} 该流水已被任一入口占用
     */
    public boolean tryRegister(String serial, String source, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO t_bank_serial_registry (serial, source, created_at)
                    VALUES (?, ?, ?)
                    """, serial, source, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 查询流水号已被哪种入口占用；未占用返回空。 */
    public Optional<String> findSource(String serial) {
        List<String> list = jdbc.query(
                "SELECT source FROM t_bank_serial_registry WHERE serial = ?",
                (rs, rowNum) -> rs.getString("source"), serial);
        return list.stream().findFirst();
    }
}
