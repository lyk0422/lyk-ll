package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.web.dto.CheckInRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 打卡测试：学生+日期去重、幂等回放、并发打卡唯一性。
 */
class CheckInServiceTest extends BaseIntegrationTest {

    @Autowired
    private CheckInService checkInService;

    private void fixtures() {
        insertVehicle(1, 30);
        insertRoute(1, 1L, "RUNNING");
        insertQualification(1, today().minusDays(1), today().plusMonths(6), "ACTIVE");
        insertStudent(1);
        assignStudent(1, 1);
    }

    @Test
    void dedupeByStudentAndDate() {
        fixtures();
        CheckInRequest.Result first = checkInService.checkIn(
                new CheckInRequest("ci-1", 1L, 1L, today()));
        CheckInRequest.Result second = checkInService.checkIn(
                new CheckInRequest("ci-2", 1L, 1L, today()));

        assertThat(first.deduped()).isFalse();
        assertThat(second.deduped()).isTrue();
        assertThat(second.checkInId()).isEqualTo(first.checkInId());
        assertThat(count("check_in")).isEqualTo(1);
    }

    @Test
    void sameStudentDifferentDatesCreatesTwoRows() {
        fixtures();
        checkInService.checkIn(new CheckInRequest("ci-d1", 1L, 1L, today()));
        checkInService.checkIn(new CheckInRequest("ci-d2", 1L, 1L, today().plusDays(1)));
        assertThat(count("check_in")).isEqualTo(2);
    }

    @Test
    void idempotentReplayReturnsSameRecord() {
        fixtures();
        CheckInRequest req = new CheckInRequest("ci-idem", 1L, 1L, today());
        CheckInRequest.Result first = checkInService.checkIn(req);
        CheckInRequest.Result replay = checkInService.checkIn(req);
        assertThat(replay).isEqualTo(first);
        assertThat(count("check_in")).isEqualTo(1);
    }

    @Test
    void concurrentCheckInsSameStudentAndDateProduceSingleRow() throws Exception {
        fixtures();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CheckInRequest.Result>> futures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return checkInService.checkIn(
                        new CheckInRequest("ci-conc-" + index, 1L, 1L, today()));
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<CheckInRequest.Result> results = futures.stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        // 全部成功且指向同一条打卡记录，数据库仅一行
        assertThat(results).hasSize(threads);
        assertThat(results.stream().map(CheckInRequest.Result::checkInId).distinct().count()).isEqualTo(1);
        assertThat(results.stream().filter(CheckInRequest.Result::deduped).count())
                .isEqualTo((long) threads - 1);
        assertThat(count("check_in")).isEqualTo(1);
    }

    @Test
    void checkInUsesClockDateWhenNotProvided() {
        fixtures();
        CheckInRequest.Result result = checkInService.checkIn(new CheckInRequest("ci-null-date", 1L, 1L, null));
        assertThat(result.checkDate()).isEqualTo(LocalDate.ofInstant(DEFAULT_NOW, java.time.ZoneOffset.UTC));
    }
}
