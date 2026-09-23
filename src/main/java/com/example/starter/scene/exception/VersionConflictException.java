package com.example.starter.scene.exception;

import java.util.List;

/**
 * 期望版本过期冲突，HTTP 409。携带当前版本及自期望版本后发生变化的对象与关节 ID。
 */
public class VersionConflictException extends RuntimeException {

    private final long currentVersion;
    private final List<String> changedObjects;
    private final List<String> changedJoints;

    public VersionConflictException(long currentVersion, List<String> changedObjects, List<String> changedJoints) {
        super("expected version is stale, current version is " + currentVersion);
        this.currentVersion = currentVersion;
        this.changedObjects = List.copyOf(changedObjects);
        this.changedJoints = List.copyOf(changedJoints);
    }

    /**
     * 当前场景版本号。
     */
    public long getCurrentVersion() {
        return currentVersion;
    }

    /**
     * 自期望版本后发生变化的对象 ID 列表（去重，升序）。
     */
    public List<String> getChangedObjects() {
        return changedObjects;
    }

    /**
     * 自期望版本后发生变化的关节名列表（去重，升序）。
     */
    public List<String> getChangedJoints() {
        return changedJoints;
    }
}
