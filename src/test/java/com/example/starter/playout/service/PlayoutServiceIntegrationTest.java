package com.example.starter.playout.service;

import com.example.starter.playout.PlayoutException;
import com.example.starter.playout.adapter.PlayoutAdapter;
import com.example.starter.playout.adapter.PlayoutCommand;
import com.example.starter.playout.clock.SyntheticClock;
import com.example.starter.playout.web.AckRequest;
import com.example.starter.playout.web.AckResponse;
import com.example.starter.playout.web.AsRunView;
import com.example.starter.playout.web.ErrorBody;
import com.example.starter.playout.web.LiveEndRequest;
import com.example.starter.playout.web.LiveStartRequest;
import com.example.starter.playout.web.LiveStartResponse;
import com.example.starter.playout.web.PlaylistItemRequest;
import com.example.starter.playout.web.PlaylistPublishRequest;
import com.example.starter.playout.web.TickRequest;
import com.example.starter.playout.web.TickResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 播出编排业务集成测试：真实 H2(MODE=MySQL) 事务/唯一约束/行锁，不 mock 数据库边界。
 * 每个场景使用独立频道名，避免顺序依赖；时钟为可控合成时钟。
 */
@SpringBootTest
class PlayoutServiceIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    @Autowired
    private PlayoutService service;
    @Autowired
    private SyntheticClock clock;
    @Autowired
    private RecordingAdapter adapter;

    @BeforeEach
    void setUp() {
        clock.setInstant(T0);
        adapter.commands.clear();
    }

    private String channel() {
        return "ch-" + UUID.randomUUID();
    }

    private static PlaylistItemRequest item(String id, String start, String end) {
        return new PlaylistItemRequest(id, "digest-" + id, Instant.parse(start), Instant.parse(end));
    }

    private void publish(String channel, String version, PlaylistItemRequest... items) {
        service.publishPlaylist(new PlaylistPublishRequest(
                channel, version, List.of(items)));
    }

    private TickResponse tick(String channel, String time) {
        return service.tick(new TickRequest(channel, Instant.parse(time)));
    }

    /** 记录适配器收到的开始指令的测试替身；优先于日志适配器注入。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class AdapterConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        RecordingAdapter recordingAdapter() {
            return new RecordingAdapter();
        }
    }

    static class RecordingAdapter implements PlayoutAdapter {
        final List<PlayoutCommand> commands = new ArrayList<>();

        @Override
        public synchronized void start(PlayoutCommand command) {
            commands.add(command);
        }
    }

    // ============================ 播单发布与时段规则 ============================

    @Test
    @DisplayName("端点相接合法：tick 后相邻节目各自成段且序号单调")
    void endpointAdjacentItemsAreValidAndSettleIntoSegments() {
        String ch = channel();
        publish(ch, "v1",
                item("A", "2026-03-01T00:00:00Z", "2026-03-01T00:10:00Z"),
                item("B", "2026-03-01T00:10:00Z", "2026-03-01T00:20:00Z"));

        TickResponse r1 = tick(ch, "2026-03-01T00:05:00Z");
        assertThat(r1.segments()).extracting(AsRunView::itemId).containsExactly("A");

        TickResponse r2 = tick(ch, "2026-03-01T00:20:00Z");
        // 本次 tick 只新生成 B；A 在 00:10 关闭。
        assertThat(r2.segments()).extracting(AsRunView::itemId).containsExactly("B");

        List<AsRunView> all = service.queryAsRun(ch, null, null);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).asRunSequence()).isEqualTo(1L);
        assertThat(all.get(0).itemId()).isEqualTo("A");
        // 首次 tick 从观察时刻建立开放段，不回填 tick 之前的历史。
        assertThat(all.get(0).start()).isEqualTo(Instant.parse("2026-03-01T00:05:00Z"));
        assertThat(all.get(0).end()).isEqualTo(Instant.parse("2026-03-01T00:10:00Z"));
        assertThat(all.get(1).asRunSequence()).isEqualTo(2L);
        assertThat(all.get(1).itemId()).isEqualTo("B");
        assertThat(all.get(1).start()).isEqualTo(Instant.parse("2026-03-01T00:10:00Z"));
        // 第二个 tick 结算到 00:20，B 的半开时段 [00:10,00:20) 在 00:20 整结束。
        assertThat(all.get(1).end()).isEqualTo(Instant.parse("2026-03-01T00:20:00Z"));
        assertThat(all).extracting(AsRunView::asRunSequence).isSorted();
    }

    @Test
    @DisplayName("正长度重叠拒绝（400）")
    void positiveOverlapRejected() {
        String ch = channel();
        assertThatThrownBy(() -> publish(ch, "v-bad",
                item("A", "2026-03-01T00:00:00Z", "2026-03-01T00:10:01Z"),
                item("B", "2026-03-01T00:10:00Z", "2026-03-01T00:20:00Z")))
                .isInstanceOf(PlayoutException.class)
                .satisfies(e -> assertThat(((PlayoutException) e).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("零/负时长、重复 itemId、非有限时间拒绝（400）")
    void malformedItemsRejected() {
        String ch = channel();
        assertThatThrownBy(() -> publish(ch, "v1",
                item("A", "2026-03-01T00:10:00Z", "2026-03-01T00:10:00Z")))
                .hasMessageContaining("positive duration");

        assertThatThrownBy(() -> publish(ch, "v2",
                item("A", "2026-03-01T00:00:00Z", "2026-03-01T00:10:00Z"),
                item("A", "2026-03-01T00:10:00Z", "2026-03-01T00:20:00Z")))
                .hasMessageContaining("duplicate itemId");

        PlaylistItemRequest infinite = new PlaylistItemRequest(
                "X", "digest-X", Instant.MIN, Instant.parse("2026-03-01T00:10:00Z"));
        assertThatThrownBy(() -> service.publishPlaylist(
                new PlaylistPublishRequest(ch, "v3", List.of(infinite))))
                .hasMessageContaining("finite");
    }

    @Test
    @DisplayName("发布失败不替换当前版本：无效版本回滚后 tick 仍按旧版本生成")
    void failedPublishRollsBackAndKeepsCurrentVersion() {
        String ch = channel();
        publish(ch, "v-good",
                item("A", "2026-03-01T00:00:00Z", "2026-03-01T01:00:00Z"));

        assertThatThrownBy(() -> publish(ch, "v-bad",
                item("B", "2026-03-01T00:00:00Z", "2026-03-01T00:10:01Z"),
                item("C", "2026-03-01T00:10:00Z", "2026-03-01T00:20:00Z")))
                .isInstanceOf(PlayoutException.class);

        // 同版本号重复发布 409。
        assertThatThrownBy(() -> publish(ch, "v-good",
                item("A2", "2026-03-01T00:00:00Z", "2026-03-01T01:00:00Z")))
                .satisfies(e -> assertThat(((PlayoutException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        TickResponse r = tick(ch, "2026-03-01T00:05:00Z");
        assertThat(r.segments()).extracting(AsRunView::itemId).containsExactly("A");
    }

    @Test
    @DisplayName("发布新版本后后续 tick 按新版本结算，历史记录保持不变")
    void newVersionAppliesToFutureTicksOnly() {
        String ch = channel();
        publish(ch, "v1",
                item("A", "2026-03-01T00:00:00Z", "2026-03-01T00:10:00Z"));
        tick(ch, "2026-03-01T00:05:00Z");
        tick(ch, "2026-03-01T00:20:00Z"); // A 关闭于 00:10，之后空档

        publish(ch, "v2",
                item("D", "2026-03-01T00:20:00Z", "2026-03-01T00:30:00Z"));
        TickResponse r = tick(ch, "2026-03-01T00:25:00Z");
        assertThat(r.segments()).extracting(AsRunView::itemId).containsExactly("D");

        List<AsRunView> all = service.queryAsRun(ch, null, null);
        assertThat(all).extracting(AsRunView::itemId).containsExactly("A", "D");
        assertThat(all.get(0).end()).isEqualTo(Instant.parse("2026-03-01T00:10:00Z"));
    }

    // ============================ tick 幂等与水位线 ============================

    @Test
    @DisplayName("重复 tick 不重复生成记录；tick 时间早于水位线 409")
    void duplicateTickIsIdempotentAndBackwardTickRejected() {
        String ch = channel();
        publish(ch, "v1", item("A", "2026-03-01T00:00:00Z", "2026-03-01T01:00:00Z"));

        TickResponse first = tick(ch, "2026-03-01T00:05:00Z");
        assertThat(first.segments()).hasSize(1);

        TickResponse repeated = tick(ch, "2026-03-01T00:05:00Z");
        assertThat(repeated.segments()).isEmpty();
        assertThat(service.queryAsRun(ch, null, null)).hasSize(1);

        assertThatThrownBy(() -> tick(ch, "2026-03-01T00:04:00Z"))
                .satisfies(e -> assertThat(((PlayoutException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("未发布播单前 tick 不产生记录")
    void tickBeforeAnyPlaylistProducesNothing() {
        String ch = channel();
        TickResponse r = tick(ch, "2026-03-01T00:05:00Z");
        assertThat(r.segments()).isEmpty();
        assertThat(service.queryAsRun(ch, null, null)).isEmpty();
    }

    // ============================ 直播插播优先级与恢复 ============================

    @Test
    @DisplayName("高优先级插播压过播单素材，结束后恢复应播素材且不补播")
    void livePreemptsPlaylistAndResumesWithoutCatchUp() {
        String ch = channel();
        publish(ch, "v1",
                item("A", "2026-03-01T00:00:00Z", "2026-03-01T00:30:00Z"),
                item("B", "2026-03-01T00:30:00Z", "2026-03-01T01:00:00Z"));
        tick(ch, "2026-03-01T00:05:00Z"); // seq1: A 开放

        ServiceResult<Object> started = service.startLive(new LiveStartRequest(
                ch, "live-1", Instant.parse("2026-03-01T00:10:00Z"), 10));
        assertThat(started.status()).isEqualTo(200);

        ServiceResult<Object> ended = service.endLive(new LiveEndRequest(
                ch, "live-1", Instant.parse("2026-03-01T00:20:00Z")));
        assertThat(ended.status()).isEqualTo(200);

        TickResponse r = tick(ch, "2026-03-01T00:40:00Z");
        // 新生成：seq2 LIVE[00:10,00:20)，seq3 A[00:20,00:30)，seq4 B[00:30,..)
        assertThat(r.segments()).hasSize(3);
        assertThat(r.segments().get(0).kind()).isEqualTo("LIVE");
        assertThat(r.segments().get(0).liveRequestId()).isEqualTo("live-1");
        assertThat(r.segments().get(0).start()).isEqualTo(Instant.parse("2026-03-01T00:10:00Z"));
        assertThat(r.segments().get(0).end()).isEqualTo(Instant.parse("2026-03-01T00:20:00Z"));
        assertThat(r.segments().get(1).itemId()).isEqualTo("A");
        assertThat(r.segments().get(1).start()).isEqualTo(Instant.parse("2026-03-01T00:20:00Z"));
        assertThat(r.segments().get(1).end()).isEqualTo(Instant.parse("2026-03-01T00:30:00Z"));
        assertThat(r.segments().get(2).itemId()).isEqualTo("B");
        assertThat(r.segments().get(2).start()).isEqualTo(Instant.parse("2026-03-01T00:30:00Z"));
        assertThat(r.segments().get(2).end()).isNull();

        List<AsRunView> all = service.queryAsRun(ch, null, null);
        // seq1 A [00:05?, 00:10)：首 tick 开放段在插播开始处关闭；被覆盖的 00:10-00:20 不补播。
        assertThat(all).hasSize(4);
        assertThat(all.get(0).kind()).isEqualTo("ITEM");
        assertThat(all.get(0).end()).isEqualTo(Instant.parse("2026-03-01T00:10:00Z"));
        var seqs = all.stream().map(AsRunView::asRunSequence).toList();
        assertThat(seqs).isSorted().doesNotHaveDuplicates();
        // 适配器收到每段一次开始指令。
        assertThat(adapter.commands).extracting(PlayoutCommand::asRunSequence)
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("播单素材在播时优先级不高于基线(0)的插播被拒；空档期允许低优先级插播")
    void nonPositivePriorityRejectedDuringPlaylistAllowedInGap() {
        String ch = channel();
        publish(ch, "v1",
                item("A", "2026-03-01T00:00:00Z", "2026-03-01T00:10:00Z"),
                item("B", "2026-03-01T00:20:00Z", "2026-03-01T00:30:00Z"));
        tick(ch, "2026-03-01T00:05:00Z");

        ServiceResult<Object> zero = service.startLive(new LiveStartRequest(
                ch, "zero-pri", Instant.parse("2026-03-01T00:06:00Z"), 0));
        assertThat(zero.status()).isEqualTo(409);
        ServiceResult<Object> negative = service.startLive(new LiveStartRequest(
                ch, "neg-pri", Instant.parse("2026-03-01T00:07:00Z"), -3));
        assertThat(negative.status()).isEqualTo(409);
        // 低优先级插播从未生成 LIVE 记录。
        tick(ch, "2026-03-01T00:08:00Z");
        assertThat(service.queryAsRun(ch, null, null))
                .allMatch(v -> "ITEM".equals(v.kind()));

        // 空档期 (00:10,00:20) 允许低优先级插播并作为实际素材播出。
        ServiceResult<Object> inGap = service.startLive(new LiveStartRequest(
                ch, "gap-live", Instant.parse("2026-03-01T00:12:00Z"), 0));
        assertThat(inGap.status()).isEqualTo(200);
        tick(ch, "2026-03-01T00:15:00Z");
        assertThat(service.queryAsRun(ch, null, null).stream()
                .map(AsRunView::liveRequestId).toList())
                .contains("gap-live");
    }

    @Test
    @DisplayName("只有更高优先级可压过当前插播；较低优先级请求 409")
    void lowerPriorityCannotPreemptHigher() {
        String ch = channel();
        publish(ch, "v1", item("A", "2026-03-01T00:00:00Z", "2026-03-01T02:00:00Z"));
        tick(ch, "2026-03-01T00:05:00Z");

        assertThat(service.startLive(new LiveStartRequest(
                ch, "high", Instant.parse("2026-03-01T00:10:00Z"), 10)).status()).isEqualTo(200);

        ServiceResult<Object> blocked = service.startLive(new LiveStartRequest(
                ch, "low", Instant.parse("2026-03-01T00:11:00Z"), 5));
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(((ErrorBody) blocked.body()).message()).contains("high");

        ServiceResult<Object> higher = service.startLive(new LiveStartRequest(
                ch, "higher", Instant.parse("2026-03-01T00:12:00Z"), 20));
        assertThat(higher.status()).isEqualTo(200);

        tick(ch, "2026-03-01T00:15:00Z");
        List<AsRunView> lives = service.queryAsRun(ch, null, null).stream()
                .filter(v -> "LIVE".equals(v.kind())).toList();
        // high 在 00:12 被 higher 抢占；low 从未播出。
        assertThat(lives).extracting(AsRunView::liveRequestId)
                .containsExactly("high", "higher");
        assertThat(lives.get(0).end()).isEqualTo(Instant.parse("2026-03-01T00:12:00Z"));
        assertThat(lives.get(1).end()).isNull();
    }

    @Test
    @DisplayName("同优先级并发请求按 requestId 字典序确定唯一胜者")
    void samePriorityConcurrentTieBreakByRequestId() throws Exception {
        String ch = channel();
        publish(ch, "v1", item("A", "2026-03-01T00:00:00Z", "2026-03-01T02:00:00Z"));
        tick(ch, "2026-03-01T00:05:00Z");

        Instant start = Instant.parse("2026-03-01T00:10:00Z");
        var outcomes = runConcurrentStarts(ch, start,
                new String[]{"req-bbb", "req-aaa"}, new int[]{10, 10});

        assertThat(outcomes.get("req-aaa").status()).isEqualTo(200);
        assertThat(outcomes.get("req-bbb").status()).isEqualTo(409);

        tick(ch, "2026-03-01T00:15:00Z");
        assertThat(service.queryAsRun(ch, null, null).stream()
                .filter(v -> "LIVE".equals(v.kind()))
                .map(AsRunView::liveRequestId).toList())
                .containsExactly("req-aaa");
    }

    @Test
    @DisplayName("不同优先级并发：高优先级即便 requestId 字典序靠后也胜出")
    void higherPriorityWinsConcurrentlyRegardlessOfRequestId() throws Exception {
        String ch = channel();
        publish(ch, "v1", item("A", "2026-03-01T00:00:00Z", "2026-03-01T02:00:00Z"));
        tick(ch, "2026-03-01T00:05:00Z");

        Instant start = Instant.parse("2026-03-01T00:10:00Z");
        var outcomes = runConcurrentStarts(ch, start,
                new String[]{"req-aaa", "req-zzz"}, new int[]{5, 10});

        assertThat(outcomes.get("req-zzz").status()).isEqualTo(200);
        assertThat(outcomes.get("req-aaa").status()).isEqualTo(409);
    }

    private java.util.Map<String, ServiceResult<Object>> runConcurrentStarts(
            String ch, Instant start, String[] requestIds, int[] priorities) throws Exception {
        int n = requestIds.length;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<ServiceResult<Object>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String requestId = requestIds[i];
                int priority = priorities[i];
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                    return service.startLive(new LiveStartRequest(ch, requestId, start, priority));
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            java.util.Map<String, ServiceResult<Object>> results = new java.util.HashMap<>();
            for (int i = 0; i < n; i++) {
                results.put(requestIds[i], futures.get(i).get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("开始时间早于水位线 409；恰等于水位线允许")
    void liveStartBeforeWatermarkRejectedAtWatermarkAllowed() {
        String ch = channel();
        publish(ch, "v1", item("A", "2026-03-01T00:00:00Z", "2026-03-01T02:00:00Z"));
        tick(ch, "2026-03-01T00:10:00Z");

        ServiceResult<Object> past = service.startLive(new LiveStartRequest(
                ch, "past", Instant.parse("2026-03-01T00:09:59Z"), 10));
        assertThat(past.status()).isEqualTo(409);
        // 失败结果也被冻结；同键同参重放仍是同一个 409。
        ServiceResult<Object> pastReplay = service.startLive(new LiveStartRequest(
                ch, "past", Instant.parse("2026-03-01T00:09:59Z"), 10));
        assertThat(pastReplay.status()).isEqualTo(409);
        assertThat(pastReplay.replay()).isTrue();

        ServiceResult<Object> atWatermark = service.startLive(new LiveStartRequest(
                ch, "at-line", Instant.parse("2026-03-01T00:10:00Z"), 10));
        assertThat(atWatermark.status()).isEqualTo(200);
    }

    // ============================ 请求指纹幂等 ============================

    @Test
    @DisplayName("同键同参返回首次冻结结果；同键异参 409（开始/结束/ACK 均适用）")
    void fingerprintFreezeAndConflict() {
        String ch = channel();
        publish(ch, "v1", item("A", "2026-03-01T00:00:00Z", "2026-03-01T02:00:00Z"));
        tick(ch, "2026-03-01T00:05:00Z");

        LiveStartRequest start1 = new LiveStartRequest(
                ch, "live-x", Instant.parse("2026-03-01T00:10:00Z"), 10);
        ServiceResult<Object> first = service.startLive(start1);
        assertThat(first.replay()).isFalse();
        ServiceResult<Object> replay = service.startLive(start1);
        assertThat(replay.replay()).isTrue();
        assertThat(((LiveStartResponse) replay.body()).requestId()).isEqualTo("live-x");

        assertThatThrownBy(() -> service.startLive(new LiveStartRequest(
                ch, "live-x", Instant.parse("2026-03-01T00:11:00Z"), 10)))
                .satisfies(e -> assertThat(((PlayoutException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        service.endLive(new LiveEndRequest(
                ch, "live-x", Instant.parse("2026-03-01T00:20:00Z")));
        assertThatThrownBy(() -> service.endLive(new LiveEndRequest(
                ch, "live-x", Instant.parse("2026-03-01T00:21:00Z"))))
                .isInstanceOf(PlayoutException.class);

        ServiceResult<Object> ack1 = service.ack(new AckRequest(ch, "ack-1", 1L));
        assertThat(ack1.replay()).isFalse();
        ServiceResult<Object> ackReplay = service.ack(new AckRequest(ch, "ack-1", 1L));
        assertThat(ackReplay.replay()).isTrue();
        assertThat(((AckResponse) ackReplay.body()).accepted()).isTrue();
        assertThatThrownBy(() -> service.ack(new AckRequest(ch, "ack-1", 2L)))
                .isInstanceOf(PlayoutException.class);
    }

    // ============================ ACK ============================

    @Test
    @DisplayName("ACK 只确认对应序号；重复/迟到 ACK 不改写其它记录状态")
    void ackOnlyConfirmsTargetSequence() {
        String ch = channel();
        publish(ch, "v1",
                item("A", "2026-03-01T00:00:00Z", "2026-03-01T00:10:00Z"),
                item("B", "2026-03-01T00:10:00Z", "2026-03-01T00:20:00Z"));
        tick(ch, "2026-03-01T00:05:00Z"); // seq1 A 开放
        tick(ch, "2026-03-01T00:20:00Z"); // seq1 A 关闭于 00:10，seq2 B 开放

        ServiceResult<Object> ack1 = service.ack(new AckRequest(ch, "ack-a", 1L));
        assertThat(((AckResponse) ack1.body()).accepted()).isTrue();
        assertThat(((AckResponse) ack1.body()).ackState()).isEqualTo("ACKED");

        // 用新键再次确认同一序号：幂等成功但不再发生状态迁移。
        ServiceResult<Object> ack1Late = service.ack(new AckRequest(ch, "ack-a-late", 1L));
        assertThat(((AckResponse) ack1Late.body()).accepted()).isFalse();
        assertThat(((AckResponse) ack1Late.body()).ackState()).isEqualTo("ACKED");

        // seq2 状态未被 seq1 的 ACK 改写。
        assertThat(service.queryAsRun(ch, null, null).get(1).ackState()).isEqualTo("PENDING");

        assertThatThrownBy(() -> service.ack(new AckRequest(ch, "ack-missing", 999L)))
                .satisfies(e -> assertThat(((PlayoutException) e).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ============================ 结束恢复与插播链 ============================

    @Test
    @DisplayName("结束高优插播时若仍有较低优插播生效，则恢复到低优插播而非播单")
    void endHighPriorityReturnsToLowerPriorityLive() {
        String ch = channel();
        publish(ch, "v1", item("A", "2026-03-01T00:00:00Z", "2026-03-01T02:00:00Z"));
        tick(ch, "2026-03-01T00:05:00Z");

        service.startLive(new LiveStartRequest(
                ch, "low", Instant.parse("2026-03-01T00:10:00Z"), 10));
        service.startLive(new LiveStartRequest(
                ch, "high", Instant.parse("2026-03-01T00:12:00Z"), 20));
        // 注意：high 抢占后 low 已 PREEMPTED 结束于 00:12，因此 high 结束后直接回播单。
        service.endLive(new LiveEndRequest(
                ch, "high", Instant.parse("2026-03-01T00:15:00Z")));
        tick(ch, "2026-03-01T00:20:00Z");

        List<AsRunView> all = service.queryAsRun(ch, null, null);
        assertThat(all).extracting(v -> v.kind() + ":" + (v.liveRequestId() != null ? v.liveRequestId() : v.itemId()))
                .containsExactly("ITEM:A", "LIVE:low", "LIVE:high", "ITEM:A");
        assertThat(all.get(1).end()).isEqualTo(Instant.parse("2026-03-01T00:12:00Z"));
        assertThat(all.get(2).start()).isEqualTo(Instant.parse("2026-03-01T00:12:00Z"));
        assertThat(all.get(2).end()).isEqualTo(Instant.parse("2026-03-01T00:15:00Z"));
        assertThat(all.get(3).start()).isEqualTo(Instant.parse("2026-03-01T00:15:00Z"));
    }

    @Test
    @DisplayName("被抢占插播再结束返回 PREEMPTED 与既有结束时刻，不重新开放")
    void preemptedLiveEndReportsPreemption() {
        String ch = channel();
        publish(ch, "v1", item("A", "2026-03-01T00:00:00Z", "2026-03-01T02:00:00Z"));
        tick(ch, "2026-03-01T00:05:00Z");
        service.startLive(new LiveStartRequest(
                ch, "low", Instant.parse("2026-03-01T00:10:00Z"), 10));
        service.startLive(new LiveStartRequest(
                ch, "high", Instant.parse("2026-03-01T00:12:00Z"), 20));

        ServiceResult<Object> ended = service.endLive(new LiveEndRequest(
                ch, "low", Instant.parse("2026-03-01T00:30:00Z")));
        var body = (com.example.starter.playout.web.LiveEndResponse) ended.body();
        assertThat(body.endKind()).isEqualTo("PREEMPTED");
        assertThat(body.end()).isEqualTo(Instant.parse("2026-03-01T00:12:00Z"));
    }
}
