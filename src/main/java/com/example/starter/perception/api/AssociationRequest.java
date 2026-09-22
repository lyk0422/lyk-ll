package com.example.starter.perception.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 关联请求：一批检测在单个事务内原子完成关联与状态迁移。
 *
 * @param detections       本批检测；服务端按（时戳, detectionId）确定顺序处理，保证重放确定性
 * @param expectedVersions 可选的乐观锁期望版本，key=trackId，value=客户端看到的当前版本；
 *                         任一不匹配则整批拒绝（409），不写入任何变化
 */
public record AssociationRequest(
        @NotEmpty @Valid List<DetectionRequest> detections,
        Map<String, Long> expectedVersions
) {
}
