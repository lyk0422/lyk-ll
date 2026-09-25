package com.example.starter.schedule.service;

import com.example.starter.schedule.AbstractIntegrationTest;
import com.example.starter.schedule.domain.ContactWindow;
import com.example.starter.schedule.web.ApiException;
import com.example.starter.schedule.web.dto.PublishResponseDto;
import com.example.starter.schedule.web.dto.ScheduleRequestDto;
import com.example.starter.schedule.web.dto.ScheduleResponseDto;
import com.example.starter.schedule.web.dto.VersionViewDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编排核心功能：半开窗口冲突、优先级抢占、同级先到保留、幂等重放、按日发布冻结。
 */
class ScheduleServiceTest extends AbstractIntegrationTest {

    @Autowired
    private ScheduleService scheduleService;

    private long satA;
    private long satB;
    private long station;

    @BeforeEach
    void setUp() {
        cleanAll();
        satA = createSatellite("SAT-A");
        satB = createSatellite("SAT-B");
        station = createStation("GS-1");
    }

    private ScheduleRequestDto request(String requestId, String satCode, String stationCode,
                                       long windowId, int priority) {
        return new ScheduleRequestDto(requestId, satCode, stationCode, windowId, priority, false);
    }

    private ScheduleRequestDto emergency(String requestId, String satCode, String stationCode,
                                         long windowId, int priority) {
        return new ScheduleRequestDto(requestId, satCode, stationCode, windowId, priority, true);
    }

    @Test
    @DisplayName("半开区间：端点相接不冲突，可同时编排")
    void adjacentEndpointsDoNotConflict() {
        ContactWindow first = window(satA, station, 1, 0, 1, 10);
        ContactWindow second = window(satB, station, 1, 10, 1, 20);

        ScheduleResponseDto r1 = scheduleService.submit(request("req-1", "SAT-A", "GS-1", first.id(), 3));
        ScheduleResponseDto r2 = scheduleService.submit(request("req-2", "SAT-B", "GS-1", second.id(), 3));

        assertThat(r1.outcome()).isEqualTo("SCHEDULED");
        assertThat(r2.outcome()).isEqualTo("SCHEDULED");
        assertThat(r2.entryId()).isNotEqualTo(r1.entryId());
    }

    @Test
    @DisplayName("内部重叠且同优先级：先到保留，后到拒绝并返回冲突详情")
    void samePriorityOverlapFirstComeServed() {
        ContactWindow first = window(satA, station, 2, 0, 2, 15);
        ContactWindow second = window(satB, station, 2, 5, 2, 20);

        ScheduleResponseDto r1 = scheduleService.submit(request("req-1", "SAT-A", "GS-1", first.id(), 3));
        ScheduleResponseDto r2 = scheduleService.submit(request("req-2", "SAT-B", "GS-1", second.id(), 3));

        assertThat(r1.outcome()).isEqualTo("SCHEDULED");
        assertThat(r2.outcome()).isEqualTo("REJECTED");
        assertThat(r2.entryId()).isNull();
        assertThat(r2.conflicts()).hasSize(1);
        assertThat(r2.conflicts().get(0).reason()).isEqualTo("SAME_PRIORITY_FIRST_COME_FIRST_SERVED");
        assertThat(r2.conflicts().get(0).requestId()).isEqualTo("req-1");
    }

    @Test
    @DisplayName("高优先级抢占：被抢占条目转 PREEMPTED 并生成重排建议")
    void higherPriorityPreemptsWithSuggestion() {
        ContactWindow low = window(satA, station, 2, 0, 2, 15);
        ContactWindow free = window(satA, station, 3, 0, 3, 10);
        ContactWindow high = window(satB, station, 2, 5, 2, 20);

        ScheduleResponseDto r1 = scheduleService.submit(request("req-low", "SAT-A", "GS-1", low.id(), 2));
        assertThat(r1.outcome()).isEqualTo("SCHEDULED");

        ScheduleResponseDto r2 = scheduleService.submit(request("req-high", "SAT-B", "GS-1", high.id(), 5));
        assertThat(r2.outcome()).isEqualTo("SCHEDULED");
        assertThat(r2.preemptions()).hasSize(1);
        assertThat(r2.preemptions().get(0).preemptedEntryId()).isEqualTo(r1.entryId());
        // 同卫星 SAT-A 下一个不冲突窗口 03:00-03:10 成为重排建议
        assertThat(r2.preemptions().get(0).suggestionWindowId()).isEqualTo(free.id());

        List<VersionViewDto> versions = scheduleService.versions("GS-1", DAY);
        var entries = versions.get(0).entries();
        var victim = entries.stream().filter(e -> e.requestId().equals("req-low")).findFirst().orElseThrow();
        var winner = entries.stream().filter(e -> e.requestId().equals("req-high")).findFirst().orElseThrow();
        assertThat(victim.status()).isEqualTo("PREEMPTED");
        assertThat(victim.preemptedByEntryId()).isEqualTo(winner.entryId());
        assertThat(winner.status()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("requestId 同键同参重放返回首次结果（replay=true）且不新增编排条目")
    void idempotentReplaySameParams() {
        ContactWindow w = window(satA, station, 2, 0, 2, 15);

        ScheduleResponseDto first = scheduleService.submit(request("req-idem", "SAT-A", "GS-1", w.id(), 3));
        ScheduleResponseDto replay = scheduleService.submit(request("req-idem", "SAT-A", "GS-1", w.id(), 3));

        assertThat(first.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.outcome()).isEqualTo("SCHEDULED");
        assertThat(replay.entryId()).isEqualTo(first.entryId());

        long entryCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_entry WHERE request_id = ?", Long.class, "req-idem");
        assertThat(entryCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("被拒绝申请同键重放仍返回 REJECTED 与同一冲突详情")
    void rejectedRequestReplayStaysRejected() {
        ContactWindow first = window(satA, station, 2, 0, 2, 15);
        ContactWindow second = window(satB, station, 2, 5, 2, 20);
        scheduleService.submit(request("req-1", "SAT-A", "GS-1", first.id(), 3));

        ScheduleResponseDto rejected = scheduleService.submit(request("req-2", "SAT-B", "GS-1", second.id(), 3));
        ScheduleResponseDto replay = scheduleService.submit(request("req-2", "SAT-B", "GS-1", second.id(), 3));
        assertThat(rejected.outcome()).isEqualTo("REJECTED");
        assertThat(replay.replay()).isTrue();
        assertThat(replay.outcome()).isEqualTo("REJECTED");
        assertThat(replay.conflicts()).hasSize(1);
        // 被拒绝的 req-2 不产生编排条目，req-1 仍为唯一活跃条目
        Long rejectedEntries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_entry WHERE request_id = ?", Long.class, "req-2");
        Long totalEntries = jdbc.queryForObject("SELECT COUNT(*) FROM schedule_entry", Long.class);
        assertThat(rejectedEntries).isZero();
        assertThat(totalEntries).isEqualTo(1L);
    }

    @Test
    @DisplayName("requestId 同键异参返回 409 且不改变编排表")
    void sameKeyDifferentParamsConflicts() {
        ContactWindow w1 = window(satA, station, 2, 0, 2, 15);
        ContactWindow w2 = window(satA, station, 3, 0, 3, 10);
        scheduleService.submit(request("req-x", "SAT-A", "GS-1", w1.id(), 3));

        assertThatThrownBy(() ->
                scheduleService.submit(request("req-x", "SAT-A", "GS-1", w2.id(), 3)))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_FINGERPRINT_MISMATCH");

        long entryCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_entry WHERE request_id = ?", Long.class, "req-x");
        assertThat(entryCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("按日发布后冻结：普通申请与撤销被拒，重复发布幂等成功")
    void publishFreezesDay() {
        ContactWindow w1 = window(satA, station, 2, 0, 2, 15);
        ContactWindow w2 = window(satB, station, 2, 5, 2, 20);
        scheduleService.submit(request("req-1", "SAT-A", "GS-1", w1.id(), 3));

        PublishResponseDto published = scheduleService.publish("GS-1", DAY);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.alreadyPublished()).isFalse();
        assertThat(published.versionNo()).isEqualTo(1);

        PublishResponseDto again = scheduleService.publish("GS-1", DAY);
        assertThat(again.alreadyPublished()).isTrue();
        assertThat(again.versionNo()).isEqualTo(1);

        assertThatThrownBy(() ->
                scheduleService.submit(request("req-2", "SAT-B", "GS-1", w2.id(), 3)))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("DAY_FROZEN");

        assertThatThrownBy(() -> scheduleService.cancel("req-1"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("DAY_FROZEN");
    }

    @Test
    @DisplayName("发布后紧急插入走新版本，旧版本仍可查")
    void emergencyInsertCreatesNewVersionAndOldVersionQueryable() {
        ContactWindow w1 = window(satA, station, 2, 0, 2, 15);
        ContactWindow w2 = window(satB, station, 5, 0, 5, 20);
        scheduleService.submit(request("req-1", "SAT-A", "GS-1", w1.id(), 3));
        scheduleService.publish("GS-1", DAY);

        ScheduleResponseDto emergencyResp =
                scheduleService.submit(emergency("req-em", "SAT-B", "GS-1", w2.id(), 4));
        assertThat(emergencyResp.outcome()).isEqualTo("SCHEDULED");

        List<VersionViewDto> versions = scheduleService.versions("GS-1", DAY);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNo()).isEqualTo(1);
        assertThat(versions.get(0).status()).isEqualTo("PUBLISHED");
        assertThat(versions.get(0).entries()).hasSize(1);
        assertThat(versions.get(0).entries().get(0).requestId()).isEqualTo("req-1");
        assertThat(versions.get(1).versionNo()).isEqualTo(2);
        assertThat(versions.get(1).status()).isEqualTo("DRAFT");
        assertThat(versions.get(1).entries()).hasSize(1);
        assertThat(versions.get(1).entries().get(0).requestId()).isEqualTo("req-em");
    }

    @Test
    @DisplayName("草稿日内允许撤销已编排条目，释放后同窗可再次编排")
    void cancelAllowedInDraft() {
        ContactWindow w1 = window(satA, station, 2, 0, 2, 15);
        ContactWindow w2 = window(satB, station, 2, 5, 2, 20);
        scheduleService.submit(request("req-1", "SAT-A", "GS-1", w1.id(), 3));

        var cancelled = scheduleService.cancel("req-1");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");

        ScheduleResponseDto r2 = scheduleService.submit(request("req-2", "SAT-B", "GS-1", w2.id(), 3));
        assertThat(r2.outcome()).isEqualTo("SCHEDULED");
    }
}
