package com.example.starter.rosbag;

import com.example.starter.rosbag.dto.*;
import com.example.starter.rosbag.service.RosbagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分段滚动、跨段组合查询及跨段恢复专项测试；分段容量配置为 3 条。
 */
@SpringBootTest(properties = "rosbag.segment-max-messages=3")
@Import(ControllableTimeSource.class)
class RosbagSegmentationTest {

    @Autowired
    private RosbagService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM rb_message_index");
        jdbc.update("DELETE FROM rb_message");
        jdbc.update("DELETE FROM rb_segment");
        jdbc.update("DELETE FROM rb_replay");
        jdbc.update("DELETE FROM rb_message_quarantine");
    }

    @Test
    void segmentsRollByConfiguredSize() {
        for (int i = 0; i < 7; i++) {
            service.append(new WriteMessageRequest("/seg", (long) i, (i + 1) * 1000L, "M",
                    "m" + i, "b" + i));
        }
        List<SegmentView> segments = service.segments("/seg");
        // 7 条消息、每段 3 条：0..2、3..5 已封段，6 在 OPEN 段
        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).messageCount()).isEqualTo(3L);
        assertThat(segments.get(0).status()).isEqualTo("SEALED");
        assertThat(segments.get(1).messageCount()).isEqualTo(3L);
        assertThat(segments.get(1).status()).isEqualTo("SEALED");
        assertThat(segments.get(2).messageCount()).isEqualTo(1L);
        assertThat(segments.get(2).status()).isEqualTo("OPEN");

        // 跨段时间范围查询：只应定位第 1、2、3 段中相交的段，结果按时间顺序
        List<MessageView> views = service.query(new QueryRequest("/seg", 2500L, 6500L, null, null));
        assertThat(views).extracting(MessageView::seq).containsExactly(2L, 3L, 4L, 5L);

        // 跨段序列范围查询
        List<MessageView> bySeq = service.query(new QueryRequest("/seg", null, null, 4L, 6L));
        assertThat(bySeq).extracting(MessageView::seq).containsExactly(4L, 5L, 6L);
    }

    @Test
    void recoveryAcrossSegmentsKeepsPrefixAndQuarantinesLaterSegments() {
        for (int i = 0; i < 7; i++) {
            service.append(new WriteMessageRequest("/seg", (long) i, (i + 1) * 1000L, "M",
                    "m" + i, "b" + i));
        }
        // 破坏第 5 条（seq=4，位于中间已封段 3..5），其后消息（4..6）应全部隔离
        service.simulateCorruptedMessage("/seg", 4);

        RecoveryResult result = service.recover("/seg");
        assertThat(result.prefixMessagesKept()).isEqualTo(4L);
        assertThat(result.incompleteMessagesQuarantined()).isEqualTo(3L);
        assertThat(result.indexRowsRebuilt()).isEqualTo(4L);

        List<MessageView> survivors = service.query(new QueryRequest("/seg", null, null, null, null));
        assertThat(survivors).extracting(MessageView::seq).containsExactly(0L, 1L, 2L, 3L);

        List<SegmentView> segments = service.segments("/seg");
        // 段 0：完整保留（0..2，标记 RECOVERED）；段 1：前缀 seq=3；
        // 原 OPEN 段 2 的消息（seq=6）被隔离，段清空但保持 OPEN 承接后续写入
        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).messageCount()).isEqualTo(3L);
        assertThat(segments.get(0).status()).isEqualTo("RECOVERED");
        assertThat(segments.get(1).messageCount()).isEqualTo(1L);
        assertThat(segments.get(1).endSeq()).isEqualTo(3L);
        assertThat(segments.get(2).messageCount()).isZero();
        assertThat(segments.get(2).status()).isEqualTo("OPEN");

        // 恢复后可继续向新 OPEN 段追加，seq 必须接前缀
        service.append(new WriteMessageRequest("/seg", 4L, 5000L, "M", "m4-fixed", "b4-fix"));
        List<MessageView> resumed = service.query(new QueryRequest("/seg", null, null, null, null));
        assertThat(resumed).extracting(MessageView::seq).containsExactly(0L, 1L, 2L, 3L, 4L);

        // 倒退 seq 仍被拒绝
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.append(
                        new WriteMessageRequest("/seg", 4L, 5001L, "M", "dup", "b-dup")))
                .isInstanceOf(com.example.starter.rosbag.exception.SequenceRegressionException.class);
    }
}
