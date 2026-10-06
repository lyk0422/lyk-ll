package com.example.starter;

import java.time.LocalDate;
import java.util.UUID;

import com.example.starter.TestClockConfig.MutableTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 集成测试基类：真实 H2（MySQL 兼容模式）+ 可控时钟，每个用例前清空全部业务表。
 */
@SpringBootTest
@Import(TestClockConfig.class)
public abstract class BaseIntegrationTest {

    /** 测试基准日期（UTC）。 */
    protected static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableTimeProvider clock;

    @BeforeEach
    void resetState() {
        clock.set(TODAY);
        jdbc.sql("DELETE FROM check_in").update();
        jdbc.sql("DELETE FROM notification").update();
        jdbc.sql("DELETE FROM reassignment").update();
        jdbc.sql("DELETE FROM suspension_trace").update();
        jdbc.sql("DELETE FROM safety_assessment").update();
        jdbc.sql("DELETE FROM qualification").update();
        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql("DELETE FROM student").update();
        jdbc.sql("DELETE FROM route").update();
        jdbc.sql("DELETE FROM vehicle").update();
    }

    /**
     * 生成唯一 requestId。
     */
    protected String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    /**
     * 插入测试车辆，返回车辆ID。
     */
    protected long insertVehicle(String plateNo, int seatCapacity) {
        jdbc.sql("INSERT INTO vehicle (plate_no, seat_capacity) VALUES (?, ?)")
                .params(plateNo, seatCapacity)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM vehicle WHERE plate_no = ?")
                .param(plateNo)
                .query(Long.class)
                .single();
    }

    /**
     * 插入测试学生，返回学生ID。
     */
    protected long insertStudent(String studentNo, Long routeId) {
        jdbc.sql("INSERT INTO student (student_no, name, route_id) VALUES (?, ?, ?)")
                .params(studentNo, "合成学生-" + studentNo, routeId)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM student WHERE student_no = ?")
                .param(studentNo)
                .query(Long.class)
                .single();
    }

    /**
     * 统计表行数。
     */
    protected long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Long.class)
                .single();
    }
}
