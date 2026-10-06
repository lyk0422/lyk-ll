package com.example.starter.rolling;

import com.example.starter.rolling.api.dto.ActualResultRequest;
import com.example.starter.rolling.api.dto.IssueRequest;
import com.example.starter.rolling.api.dto.RollActualRequest;
import com.example.starter.rolling.api.dto.RollWindowRequest;
import com.example.starter.rolling.api.dto.ScheduleCreateRequest;
import com.example.starter.rolling.api.dto.ScheduleItemRequest;
import com.example.starter.rolling.api.dto.ScheduleView;
import com.example.starter.rolling.service.RollingScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * H2（MODE=MySQL）全栈测试基类：每个场景前清空业务表并重置取号序列与档位数据。
 */
@SpringBootTest
public abstract class AbstractRollingIntegrationTest {

    protected static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 25, 8, 0);

    @Autowired
    protected RollingScheduleService service;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM roll_late_event");
        jdbc.update("DELETE FROM roll_change_actual");
        jdbc.update("DELETE FROM roll_ledger");
        jdbc.update("DELETE FROM late_actual_event");
        jdbc.update("DELETE FROM actual_result");
        jdbc.update("DELETE FROM roll_change_window");
        jdbc.update("DELETE FROM schedule_item");
        jdbc.update("DELETE FROM schedule_order");
        jdbc.update("DELETE FROM effective_version");
        jdbc.update("DELETE FROM idempotent_record");
        jdbc.update("UPDATE id_alloc SET next_val = 1 WHERE seq_name = 'global'");
        Integer gradeCount = jdbc.queryForObject("SELECT COUNT(1) FROM width_grade", Integer.class);
        if (gradeCount == null || gradeCount == 0) {
            jdbc.update("""
                    MERGE INTO width_grade (width_mm, grade_no) KEY (width_mm) VALUES
                      (1250,1),(1200,2),(1150,3),(1100,4),(1050,5),(1000,6)
                    """);
        }
    }

    protected ScheduleItemRequest item(String contract, int width, String km, boolean warmup, int startHour) {
        return new ScheduleItemRequest(contract, width, new BigDecimal(km), warmup,
                T0.plusHours(startHour), T0.plusHours(startHour + 1));
    }

    protected ScheduleItemRequest item(String contract, int width, String km, boolean warmup,
                                       LocalDateTime start, LocalDateTime end) {
        return new ScheduleItemRequest(contract, width, new BigDecimal(km), warmup, start, end);
    }

    protected RollWindowRequest window(int startHour, int endHour) {
        return new RollWindowRequest(T0.plusHours(startHour), T0.plusHours(endHour));
    }

    protected ScheduleCreateRequest createRequest(String requestId, String line, String unit,
                                                  Long baseVersionId,
                                                  List<ScheduleItemRequest> items,
                                                  List<RollWindowRequest> windows) {
        return new ScheduleCreateRequest(requestId, line, unit, baseVersionId, items, windows);
    }

    protected ScheduleCreateRequest simpleOrder(String requestId, String line,
                                                List<ScheduleItemRequest> items) {
        return createRequest(requestId, line, "U1", null, items, List.of());
    }

    protected ScheduleView draftThenValidateThenIssue(String requestId, String line,
                                                      List<ScheduleItemRequest> items) {
        ScheduleView draft = service.createOrRevise(simpleOrder(requestId, line, items));
        service.validate(draft.id());
        return service.issue(draft.id(), new IssueRequest("issue-" + requestId));
    }

    protected ActualResultRequest actual(String requestId, String line, String contract,
                                         int passNo, String km, LocalDateTime recordedAt) {
        return new ActualResultRequest(requestId, line, contract, passNo, new BigDecimal(km), recordedAt);
    }

    protected ActualResultRequest actual(String requestId, String line, String contract,
                                         int passNo, String km) {
        return actual(requestId, line, contract, passNo, km, T0.plusHours(passNo));
    }

    protected List<ScheduleItemRequest> items(ScheduleItemRequest... items) {
        return new ArrayList<>(List.of(items));
    }

    /**
     * 造一条合成轧辊台账（周期上限公里 / 累计公里 / 已轧卷数 / 在机 / 禁排）。
     */
    protected void seedRoll(String rollNo, String limitKm, String totalKm, int coilsUsed,
                            boolean onMachine, boolean banned) {
        jdbc.update("""
                INSERT INTO roll_ledger (roll_no, cycle_limit_km, total_km, coils_used, on_machine, banned)
                VALUES (?,?,?,?,?,?)
                """, rollNo, new BigDecimal(limitKm), new BigDecimal(totalKm), coilsUsed,
                onMachine, banned);
    }

    protected RollActualRequest rollActual(String requestId, String rollNo, String km,
                                           LocalDateTime recordedAt) {
        return new RollActualRequest(requestId, rollNo, new BigDecimal(km), recordedAt);
    }

    protected RollActualRequest rollActual(String requestId, String rollNo, String km, int hourOffset) {
        return rollActual(requestId, rollNo, km, T0.plusHours(hourOffset));
    }
}
