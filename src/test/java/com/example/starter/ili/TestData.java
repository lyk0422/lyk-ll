package com.example.starter.ili;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 集成测试共享的合成数据构造器。
 * 标准样本：run1=2023-01-01，run2=2024-01-01（恰好 365 天，深度差即年化增长率）。
 */
final class TestData {

    static final String SEGMENT_CODE = "SEG-01";

    private TestData() {
    }

    static Dtos.DefectPayload defect(String ref, String mileage, String depth) {
        return new Dtos.DefectPayload(ref, new BigDecimal(mileage), new BigDecimal(depth));
    }

    static Dtos.CreateSegmentRequest segmentRequest() {
        return new Dtos.CreateSegmentRequest(SEGMENT_CODE, "示例管段", List.of(
                new Dtos.WeldPayload("W-001", new BigDecimal("100.0")),
                new Dtos.WeldPayload("W-002", new BigDecimal("200.0"))));
    }

    /**
     * 标准双回合导入样本：
     * A 深度 10->14（+4，稳定），B 10->18（+8，增长），C 10->30（+20，严重），
     * E 仅旧回合存在（已消失），D 仅新回合存在（新发现）。
     */
    static Dtos.ImportAlignmentRequest sampleImport(String requestId) {
        Dtos.RunPayload run1 = new Dtos.RunPayload("ILI-2023", LocalDate.of(2023, 1, 1), List.of(
                defect("A", "100.0", "10"),
                defect("B", "200.0", "10"),
                defect("C", "300.0", "10"),
                defect("E", "500.0", "10")));
        Dtos.RunPayload run2 = new Dtos.RunPayload("ILI-2024", LocalDate.of(2024, 1, 1), List.of(
                defect("A", "100.3", "14"),
                defect("B", "200.4", "18"),
                defect("C", "299.7", "30"),
                defect("D", "400.0", "12")));
        return new Dtos.ImportAlignmentRequest(requestId, SEGMENT_CODE, run1, run2);
    }

    /** 构造 N 对增长缺陷（每对深度 +8，分级 GROWING）的导入请求，用于并发测试。 */
    static Dtos.ImportAlignmentRequest growingPairsImport(String requestId, int pairCount) {
        List<Dtos.DefectPayload> run1Defects = new java.util.ArrayList<>();
        List<Dtos.DefectPayload> run2Defects = new java.util.ArrayList<>();
        for (int i = 0; i < pairCount; i++) {
            BigDecimal mileage = BigDecimal.valueOf(1000L + i * 100L);
            run1Defects.add(new Dtos.DefectPayload("G" + i, mileage, new BigDecimal("10")));
            run2Defects.add(new Dtos.DefectPayload("G" + i, mileage.add(new BigDecimal("0.2")),
                    new BigDecimal("18")));
        }
        return new Dtos.ImportAlignmentRequest(requestId, SEGMENT_CODE,
                new Dtos.RunPayload("ILI-2023", LocalDate.of(2023, 1, 1), run1Defects),
                new Dtos.RunPayload("ILI-2024", LocalDate.of(2024, 1, 1), run2Defects));
    }
}
