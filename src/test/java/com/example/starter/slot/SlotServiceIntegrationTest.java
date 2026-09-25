package com.example.starter.slot;

import com.example.starter.slot.dto.PublicationView;
import com.example.starter.slot.dto.ReturnResultView;
import com.example.starter.slot.dto.SlotResultView;
import com.example.starter.slot.dto.SlotSeriesRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时刻协调核心规则集成测试：使用真实 H2（MODE=MySQL）验证数据库约束与事务边界。
 */
@SpringBootTest
class SlotServiceIntegrationTest {

    @Autowired
    private SlotService slotService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM slot_allocation");
        jdbc.update("DELETE FROM slot_request");
        jdbc.update("DELETE FROM slot_snapshot");
        jdbc.update("DELETE FROM slot_history");
        jdbc.update("DELETE FROM slot_publication");
        jdbc.update("INSERT INTO slot_publication(id, published_at) VALUES (1, NULL)");
    }

    private void seedHistory(String airline, String flightNo, String slotTime,
                             int executed, int planned) {
        jdbc.update("INSERT INTO slot_history(airline, flight_no, slot_time, executed, planned)"
                + " VALUES (?,?,?,?,?)", airline, flightNo, slotTime, executed, planned);
    }

    private SlotSeriesRequest req(String requestId, String airline, String flightNo,
                                  String slotTime, Integer... weeks) {
        return new SlotSeriesRequest(requestId, airline, flightNo, slotTime, List.of(weeks));
    }

    private int count(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void retainedWhenHistoricalRateExactly80Percent() {
        // 执行率恰好 80%（8/10）视为达标，保留原时刻并自动占用
        seedHistory("CA", "CA1235", "09:00", 8, 10);
        SlotResultView view = slotService.apply(req("REQ-80", "CA", "CA1235", "09:00", 1, 2));
        assertEquals("ALLOCATED", view.status());
        assertEquals(2, view.allocations().size());
        assertTrue(view.allocations().stream().allMatch(a -> "RETAINED".equals(a.type())));

        // 保留时刻自动占用，其他航司申请同时刻同周被拒绝
        SlotConflictException ex = assertThrows(SlotConflictException.class,
                () -> slotService.apply(req("REQ-80-B", "MU", "MU200", "09:00", 1)));
        assertEquals("SLOT_CONFLICT", ex.getCode());
        assertEquals("CA", ex.getConflicts().get(0).holder());
    }

    @Test
    void poolWhenHistoricalRateIs799PerMille() {
        // 79.9%（799/1000）未达标，进入公共池
        seedHistory("CZ", "CZ3001", "11:05", 799, 1000);
        SlotResultView view = slotService.apply(req("REQ-799", "CZ", "CZ3001", "11:05", 1));
        assertTrue(view.allocations().stream().allMatch(a -> "POOL".equals(a.type())));
    }

    @Test
    void poolWhenNoHistory() {
        SlotResultView view = slotService.apply(req("REQ-NH", "CA", "CA999", "12:00", 1));
        assertTrue(view.allocations().stream().allMatch(a -> "POOL".equals(a.type())));
    }

    @Test
    void conflictJudgedPerSlotAndWeek() {
        slotService.apply(req("REQ-A", "CA", "CA100", "08:30", 1));
        // 同时刻同周冲突，返回明确冲突清单
        SlotConflictException ex = assertThrows(SlotConflictException.class,
                () -> slotService.apply(req("REQ-B", "MU", "MU200", "08:30", 1)));
        assertEquals("SLOT_CONFLICT", ex.getCode());
        assertEquals(1, ex.getConflicts().size());
        assertEquals(1, ex.getConflicts().get(0).week());
        assertEquals("CA", ex.getConflicts().get(0).holder());
        // 同时刻不同周不冲突
        SlotResultView other = slotService.apply(req("REQ-B2", "MU", "MU200", "08:30", 2));
        assertEquals("ALLOCATED", other.status());
    }

    @Test
    void seriesRollsBackWhenAnyWeekConflicts() {
        slotService.apply(req("REQ-A", "CA", "CA100", "08:30", 2));
        // 第 2 周冲突，整个系列（1,2,3 周）必须整体回滚
        assertThrows(SlotConflictException.class,
                () -> slotService.apply(req("REQ-B", "MU", "MU200", "08:30", 1, 2, 3)));
        assertEquals(0, count("SELECT COUNT(*) FROM slot_allocation WHERE request_id='REQ-B'"));
        assertEquals(0, count("SELECT COUNT(*) FROM slot_request WHERE request_id='REQ-B'"));
        // 第 1、3 周不得被部分占用
        assertEquals(1, count("SELECT COUNT(*) FROM slot_allocation WHERE slot_time='08:30'"));
    }

    @Test
    void idempotentReplaySameParamsReturnsFirstResult() {
        SlotResultView first = slotService.apply(req("REQ-I", "CA", "CA100", "08:30", 3, 1, 3));
        // 覆盖周顺序与重复不同，但规范化表示相同，视为同参重放
        SlotResultView replay = slotService.apply(req("REQ-I", "CA", "CA100", "08:30", 1, 3));
        assertEquals(first.requestId(), replay.requestId());
        assertEquals(first.allocations(), replay.allocations());
        assertEquals("ALLOCATED", replay.status());
        // 周去重后仅 1、3 两周各一条占用
        assertEquals(2, count("SELECT COUNT(*) FROM slot_allocation WHERE request_id='REQ-I'"));
    }

    @Test
    void idempotentMismatchRejected() {
        slotService.apply(req("REQ-M", "CA", "CA100", "08:30", 1));
        // 同 requestId 不同时刻 → 409
        SlotConflictException ex = assertThrows(SlotConflictException.class,
                () -> slotService.apply(req("REQ-M", "CA", "CA100", "09:30", 1)));
        assertEquals("IDEMPOTENT_MISMATCH", ex.getCode());
        // 同 requestId 不同覆盖周 → 409
        assertThrows(SlotConflictException.class,
                () -> slotService.apply(req("REQ-M", "CA", "CA100", "08:30", 1, 2)));
    }

    @Test
    void releaseFreesSlotImmediatelyForOthers() {
        slotService.apply(req("REQ-A", "CA", "CA100", "08:30", 1));
        ReturnResultView returned = slotService.release("REQ-A");
        assertEquals(1, returned.released().size());
        assertEquals("08:30", returned.released().get(0).slotTime());
        // 退回后立即可被他人申请
        SlotResultView other = slotService.apply(req("REQ-B", "MU", "MU200", "08:30", 1));
        assertEquals("ALLOCATED", other.status());
        // 重复退回幂等，返回空清单
        ReturnResultView again = slotService.release("REQ-A");
        assertTrue(again.released().isEmpty());
        // 退回不影响他人已占用的时刻
        assertEquals(1, count("SELECT COUNT(*) FROM slot_allocation WHERE slot_time='08:30'"));
    }

    @Test
    void releaseUnknownRequestThrowsNotFound() {
        assertThrows(SlotNotFoundException.class, () -> slotService.release("REQ-NOPE"));
        assertThrows(SlotNotFoundException.class, () -> slotService.getRequest("REQ-NOPE"));
    }

    @Test
    void publishFreezesPeriodAndIsIdempotent() {
        slotService.apply(req("REQ-A", "CA", "CA100", "08:30", 1));
        PublicationView first = slotService.publish();
        assertFalse(first.alreadyPublished());
        assertEquals(1, first.publishedCount());
        assertEquals(1, first.snapshot().size());

        // 重复发布幂等成功，快照不变
        PublicationView second = slotService.publish();
        assertTrue(second.alreadyPublished());
        assertEquals(first.snapshot(), second.snapshot());

        // 发布后新申请一律拒绝
        assertThrows(PeriodFrozenException.class,
                () -> slotService.apply(req("REQ-LATE", "MU", "MU200", "10:00", 1)));

        // 已存在系列的同参重放仍返回首次结果（幂等优先于冻结）
        SlotResultView replay = slotService.apply(req("REQ-A", "CA", "CA100", "08:30", 1));
        assertEquals("ALLOCATED", replay.status());
    }

    @Test
    void lateReturnDoesNotAlterPublishedSnapshot() {
        slotService.apply(req("REQ-A", "CA", "CA100", "08:30", 1));
        PublicationView published = slotService.publish();

        // 发布后迟到退回：占用表释放，快照不变
        ReturnResultView returned = slotService.release("REQ-A");
        assertEquals(1, returned.released().size());
        assertEquals(0, count("SELECT COUNT(*) FROM slot_allocation"));

        PublicationView again = slotService.publish();
        assertTrue(again.alreadyPublished());
        assertEquals(1, again.publishedCount());
        assertEquals(published.snapshot(), again.snapshot());
    }

    @Test
    void rejectsNonFiveMinuteGranularity() {
        assertThrows(IllegalArgumentException.class,
                () -> slotService.apply(req("REQ-G", "CA", "CA100", "08:32", 1)));
    }

    @Test
    void rejectsInvalidWeeks() {
        assertThrows(IllegalArgumentException.class,
                () -> slotService.apply(req("REQ-W", "CA", "CA100", "08:30", 0)));
    }
}
