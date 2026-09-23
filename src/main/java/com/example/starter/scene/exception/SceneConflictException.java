package com.example.starter.scene.exception;

import java.util.List;

/**
 * 版本冲突或幂等键冲突，对应 HTTP 409。
 */
public class SceneConflictException extends RuntimeException {

    private final String code;
    private final long expectedVersion;
    private final long currentVersion;
    private final List<String> changedObjectIds;
    private final List<String> changedJointNames;
    private final boolean robotPoseChanged;

    public SceneConflictException(String code, String message, long expectedVersion, long currentVersion,
                                  List<String> changedObjectIds, List<String> changedJointNames,
                                  boolean robotPoseChanged) {
        super(message);
        this.code = code;
        this.expectedVersion = expectedVersion;
        this.currentVersion = currentVersion;
        this.changedObjectIds = changedObjectIds;
        this.changedJointNames = changedJointNames;
        this.robotPoseChanged = robotPoseChanged;
    }

    public String getCode() {
        return code;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getCurrentVersion() {
        return currentVersion;
    }

    public List<String> getChangedObjectIds() {
        return changedObjectIds;
    }

    public List<String> getChangedJointNames() {
        return changedJointNames;
    }

    public boolean isRobotPoseChanged() {
        return robotPoseChanged;
    }
}
