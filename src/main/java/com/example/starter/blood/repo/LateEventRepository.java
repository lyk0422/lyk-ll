package com.example.starter.blood.repo;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.example.starter.blood.domain.TestOutcome;
import com.example.starter.blood.domain.TestType;
import com.example.starter.blood.domain.UnitStatus;

/**
 * 迟到事件数据访问：终态后回传的检测结果只记录、不改变状态。
 */
@Repository
public class LateEventRepository {

    private final JdbcClient jdbc;

    public LateEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录迟到事件。
     *
     * @param barcode    条码
     * @param testType   检测项
     * @param outcome    回传结果
     * @param unitStatus 回传到达时单位所处终态
     * @param now        记录时刻（UTC）
     */
    public void insert(String barcode, TestType testType, TestOutcome outcome, UnitStatus unitStatus, Instant now) {
        jdbc.sql("INSERT INTO late_event (barcode, test_type, result, unit_status, created_at)"
                        + " VALUES (:barcode, :testType, :result, :unitStatus, :createdAt)")
                .param("barcode", barcode)
                .param("testType", testType.name())
                .param("result", outcome.name())
                .param("unitStatus", unitStatus.name())
                .param("createdAt", Timestamp.from(now))
                .update();
    }

    /**
     * 统计指定条码的迟到事件数（测试与核对用）。
     *
     * @param barcode 条码
     * @return 事件数
     */
    public long countByBarcode(String barcode) {
        return jdbc.sql("SELECT COUNT(*) FROM late_event WHERE barcode = :barcode")
                .param("barcode", barcode)
                .query(Long.class)
                .single();
    }
}
