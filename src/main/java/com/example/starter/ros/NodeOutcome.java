package com.example.starter.ros;

/**
 * 单个目标节点的事务结果。
 *
 * @param status           结果状态
 * @param reason           拒绝/超时/冲突原因；接受时为 null
 * @param expectedVersion  请求中携带的期望版本
 * @param currentVersion   该节点当前已提交版本（提交后为新版本）
 */
public record NodeOutcome(
        OutcomeStatus status,
        String reason,
        long expectedVersion,
        long currentVersion) {

    public static NodeOutcome accepted(long expectedVersion, long currentVersion) {
        return new NodeOutcome(OutcomeStatus.ACCEPTED, null, expectedVersion, currentVersion);
    }

    public static NodeOutcome rejected(long expectedVersion, long currentVersion, String reason) {
        return new NodeOutcome(OutcomeStatus.REJECTED, reason, expectedVersion, currentVersion);
    }

    public static NodeOutcome timeout(long expectedVersion, long currentVersion) {
        return new NodeOutcome(OutcomeStatus.TIMEOUT, "node did not respond within configured timeout",
                expectedVersion, currentVersion);
    }

    public static NodeOutcome versionConflict(long expectedVersion, long currentVersion) {
        return new NodeOutcome(OutcomeStatus.VERSION_CONFLICT,
                "expectedVersion " + expectedVersion + " but current version is " + currentVersion,
                expectedVersion, currentVersion);
    }
}
