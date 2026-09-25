package com.example.starter.repository;

import java.sql.Timestamp;
import java.time.Instant;

import com.example.starter.domain.TestResultValue;
import com.example.starter.domain.TestType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 检测结果回传事件流水数据访问，含终态后的迟到事件。
 */
@Repository
public class ResultEventRepository {

    private final JdbcClient jdbc;

    public ResultEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一次结果回传事件。
     *
     * @param late 是否迟到事件（单位已处终态，不改变状态）
     */
    public void insert(String barcode, TestType testType, TestResultValue result, boolean late, Instant now) {
        jdbc.sql("INSERT INTO result_event (barcode, test_type, result, late, recorded_at)"
                        + " VALUES (:barcode, :testType, :result, :late, :now)")
                .param("barcode", barcode)
                .param("testType", testType.name())
                .param("result", result.name())
                .param("late", late)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * 统计指定条码的迟到事件数，用于测试断言终态不复活。
     */
    public long countLateByBarcode(String barcode) {
        return jdbc.sql("SELECT COUNT(*) FROM result_event WHERE barcode = :barcode AND late = TRUE")
                .param("barcode", barcode)
                .query(Long.class)
                .single();
    }
}
