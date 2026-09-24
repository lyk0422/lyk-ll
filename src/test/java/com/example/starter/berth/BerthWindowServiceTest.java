package com.example.starter.berth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 靠泊窗口业务测试：基于真实 H2（MySQL 兼容模式）验证冲突、幂等、状态机与改期。
 * 合成数据：泊位1限深12.5米、泊位2限深8.0米；引航员3名；
 * 允许时段 2026-09-25 UTC 00:00-12:00 与 13:00-23:00。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class BerthWindowServiceTest {

    private static final long BERTH1 = 1L;
    private static final long BERTH2 = 2L;
    private static final Instant T01 = Instant.parse("2026-09-25T01:00:00Z");
    private static final Instant T02 = Instant.parse("2026-09-25T02:00:00Z");
    private static final Instant T03 = Instant.parse("2026-09-25T03:00:00Z");
    private static final Instant T04 = Instant.parse("2026-09-25T04:00:00Z");
    private static final Instant T05 = Instant.parse("2026-09-25T05:00:00Z");
    private static final Instant T06 = Instant.parse("2026-09-25T06:00:00Z");
    private static final Instant T08 = Instant.parse("2026-09-25T08:00:00Z");

    @Autowired
    private BerthWindowService service;
    @Autowired
    private BerthWindowRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM window_pilot");
        jdbc.update("DELETE FROM berth_window");
        clock.setInstant(Instant.parse("2026-09-24T00:00:00Z"));
    }

    private CreateBerthWindowRequest req(String requestId, long berthId, Instant start, Instant end,
            String draft, int pilots) {
        return new CreateBerthWindowRequest(requestId, "VESSEL-" + requestId, berthId, start, end,
                new BigDecimal(draft), pilots);
    }

    @Test
    void 半开区间内部重叠冲突_端点相接不冲突() {
        BerthWindowResponse a = service.create(req("A1", BERTH1, T01, T03, "10.0", 1));
        service.approve(a.id());

        // 内部重叠 [02:00,04:00) 与已批准 [01:00,03:00) 冲突
        ApiException overlap = assertThrows(ApiException.class,
                () -> service.create(req("A2", BERTH1, T02, T04, "10.0", 1)));
        assertEquals(HttpStatus.CONFLICT, overlap.status());

        // 端点相接 [03:00,05:00) 不算冲突
        BerthWindowResponse touching = service.create(req("A3", BERTH1, T03, T05, "10.0", 1));
        assertEquals(BerthWindowStatus.PENDING, touching.status());

        // 不同泊位同时段不冲突
        BerthWindowResponse otherBerth = service.create(req("A4", BERTH2, T01, T03, "5.0", 1));
        assertEquals(BerthWindowStatus.PENDING, otherBerth.status());
    }

    @Test
    void 引航员同一时段最多服务一艘船_不重叠可复用() {
        BerthWindowResponse w1 = service.create(req("P1", BERTH1, T01, T03, "10.0", 2));
        service.approve(w1.id());
        assertEquals(List.of(1L, 2L), service.get(w1.id()).pilotIds());

        // 重叠时段只剩 1 名空闲引航员，需要 2 名 -> 409
        ApiException shortage = assertThrows(ApiException.class,
                () -> service.create(req("P2", BERTH2, T02, T04, "5.0", 2)));
        assertEquals(HttpStatus.CONFLICT, shortage.status());

        // 端点相接不重叠，引航员可复用
        BerthWindowResponse w3 = service.create(req("P3", BERTH2, T03, T05, "5.0", 2));
        service.approve(w3.id());
        assertEquals(List.of(1L, 2L), service.get(w3.id()).pilotIds());
    }

    @Test
    void 吃水超过限深直接拒绝且不产生记录() {
        ApiException e = assertThrows(ApiException.class,
                () -> service.create(req("D1", BERTH2, T01, T03, "8.5", 1)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status());
        assertTrue(repository.findByRequestId("D1").isEmpty());
    }

    @Test
    void 申请起止须完整落在允许时段内_端点等于边界合法() {
        // 跨两段允许时段的空档 [11:00,13:00) -> 拒绝
        ApiException e = assertThrows(ApiException.class, () -> service.create(
                req("W1", BERTH1, Instant.parse("2026-09-25T11:00:00Z"),
                        Instant.parse("2026-09-25T13:00:00Z"), "10.0", 1)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status());
        assertTrue(repository.findByRequestId("W1").isEmpty());

        // 端点恰等于允许时段边界 [10:00,12:00] -> 合法
        BerthWindowResponse ok = service.create(req("W2", BERTH1,
                Instant.parse("2026-09-25T10:00:00Z"), Instant.parse("2026-09-25T12:00:00Z"), "10.0", 1));
        assertEquals(BerthWindowStatus.PENDING, ok.status());
    }

    @Test
    void 同键重放返回首次结果_同键异参返回409且不新增记录() {
        BerthWindowResponse first = service.create(req("I1", BERTH1, T01, T03, "10.0", 1));
        BerthWindowResponse replay = service.create(req("I1", BERTH1, T01, T03, "10.0", 1));
        assertEquals(first.id(), replay.id());
        assertEquals(first, replay);

        ApiException e = assertThrows(ApiException.class,
                () -> service.create(req("I1", BERTH1, T02, T04, "10.0", 1)));
        assertEquals(HttpStatus.CONFLICT, e.status());

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM berth_window WHERE request_id = 'I1'",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void 审批时重新校验冲突_两个待审批窗口只能批准一个() {
        BerthWindowResponse w1 = service.create(req("C1", BERTH1, T01, T03, "10.0", 1));
        BerthWindowResponse w2 = service.create(req("C2", BERTH1, T02, T04, "10.0", 1));
        service.approve(w1.id());
        ApiException e = assertThrows(ApiException.class, () -> service.approve(w2.id()));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(BerthWindowStatus.PENDING, service.get(w2.id()).status());
    }

    @Test
    void 到达起始时刻自动生效_登记离泊后完成() {
        BerthWindowResponse w = service.create(req("S1", BERTH1, T01, T03, "10.0", 1));
        service.approve(w.id());
        assertEquals(BerthWindowStatus.APPROVED, service.get(w.id()).status());

        clock.setInstant(T01);
        assertEquals(BerthWindowStatus.ACTIVE, service.get(w.id()).status());

        BerthWindowResponse done = service.complete(w.id());
        assertEquals(BerthWindowStatus.COMPLETED, done.status());
        assertTrue(done.pilotIds().isEmpty());
    }

    @Test
    void 终态幂等_重复相同操作成功_迟到操作不得复活() {
        BerthWindowResponse w = service.create(req("T1", BERTH1, T01, T03, "10.0", 1));
        service.reject(w.id());
        // 重复驳回：幂等成功
        assertEquals(BerthWindowStatus.REJECTED, service.reject(w.id()).status());
        // 迟到审批不得复活
        ApiException e = assertThrows(ApiException.class, () -> service.approve(w.id()));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(BerthWindowStatus.REJECTED, service.get(w.id()).status());

        BerthWindowResponse w2 = service.create(req("T2", BERTH1, T06, T08, "10.0", 1));
        service.approve(w2.id());
        service.complete(w2.id());
        // 重复离泊登记：幂等成功
        assertEquals(BerthWindowStatus.COMPLETED, service.complete(w2.id()).status());
        // 终态后取消/驳回均拒绝
        assertThrows(ApiException.class, () -> service.cancel(w2.id()));
        assertThrows(ApiException.class, () -> service.reject(w2.id()));
        assertEquals(BerthWindowStatus.COMPLETED, service.get(w2.id()).status());

        BerthWindowResponse w3 = service.create(req("T3", BERTH1, T06, T08, "10.0", 1));
        service.cancel(w3.id());
        // 重复取消：幂等成功；取消后审批拒绝
        assertEquals(BerthWindowStatus.CANCELLED, service.cancel(w3.id()).status());
        assertThrows(ApiException.class, () -> service.approve(w3.id()));
    }

    @Test
    void 改期视为新申请_重新走完整冲突校验_与自身不算冲突() {
        BerthWindowResponse a = service.create(req("R1", BERTH1, T01, T03, "10.0", 1));
        service.approve(a.id());
        BerthWindowResponse b = service.create(req("R2", BERTH1, T06, T08, "10.0", 1));
        service.approve(b.id());

        // 改期到与 B 重叠 -> 409，原窗口保持已批准
        ApiException e = assertThrows(ApiException.class, () -> service.reschedule(a.id(),
                new RescheduleRequest("R1-NEW", Instant.parse("2026-09-25T07:00:00Z"),
                        Instant.parse("2026-09-25T09:00:00Z"))));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(BerthWindowStatus.APPROVED, service.get(a.id()).status());

        // 改期到与自身原时段重叠 [02:00,04:00)：与自身不算冲突，成功
        BerthWindowResponse moved = service.reschedule(a.id(), new RescheduleRequest("R1-NEW2", T02, T04));
        assertEquals(BerthWindowStatus.PENDING, moved.status());
        assertEquals(BerthWindowStatus.CANCELLED, service.get(a.id()).status());

        // 新申请需重新审批；同 requestId 重放返回首次结果
        BerthWindowResponse replay = service.reschedule(a.id(), new RescheduleRequest("R1-NEW2", T02, T04));
        assertEquals(moved.id(), replay.id());
        service.approve(moved.id());
        assertEquals(BerthWindowStatus.APPROVED, service.get(moved.id()).status());
    }

    @Test
    void 指纹序列化消除字段拼接歧义() {
        String f1 = BerthWindowService.fingerprint("ab", "c");
        String f2 = BerthWindowService.fingerprint("a", "bc");
        assertFalse(f1.equals(f2));
        assertEquals(BerthWindowService.fingerprint("x", "y"), BerthWindowService.fingerprint("x", "y"));
    }
}
