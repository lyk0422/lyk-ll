package com.example.starter.support;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import com.example.starter.segment.SegmentDtos.CreateSegmentRequest;
import com.example.starter.segment.SegmentDtos.SegmentResponse;
import com.example.starter.segment.SegmentService;
import com.example.starter.tree.TreeDtos.RegisterTreeRequest;
import com.example.starter.tree.TreeDtos.TreeResponse;
import com.example.starter.tree.TreeService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：每个用例前清空全部业务表并重置可控时钟，避免顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {

    protected static final LocalDate DAY_ZERO = LocalDate.of(2026, 1, 5);

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected SegmentService segmentService;

    @Autowired
    protected TreeService treeService;

    private final AtomicLong requestSeq = new AtomicLong();

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM operation_report");
        jdbcTemplate.update("DELETE FROM segment_active_permit");
        jdbcTemplate.update("DELETE FROM work_permit");
        jdbcTemplate.update("DELETE FROM pruning_task");
        jdbcTemplate.update("DELETE FROM tree");
        jdbcTemplate.update("DELETE FROM segment_freeze");
        jdbcTemplate.update("DELETE FROM gale_warning");
        jdbcTemplate.update("DELETE FROM road_segment");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        clock.setDate(DAY_ZERO);
    }

    /** 生成用例内唯一 requestId。 */
    protected String nextRequestId(String prefix) {
        return prefix + "-" + requestSeq.incrementAndGet();
    }

    protected SegmentResponse createSegment(String name) {
        return segmentService.create(new CreateSegmentRequest(nextRequestId("seg"), name));
    }

    /** 注册一级风险树木（score 72），返回含 7 日任务的响应。 */
    protected TreeResponse registerLevelOneTree(long segmentId, String treeCode) {
        return treeService.register(new RegisterTreeRequest(nextRequestId("tree"), segmentId, treeCode,
                "悬铃木", 60, 35, 18.0, true));
    }

    /** 注册四级风险树木（score 8），不生成任务。 */
    protected TreeResponse registerLevelFourTree(long segmentId, String treeCode) {
        return treeService.register(new RegisterTreeRequest(nextRequestId("tree"), segmentId, treeCode,
                "桂花", 10, 90, 2.0, false));
    }
}
