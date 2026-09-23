package com.example.starter.rail.dto;

import com.example.starter.rail.model.PlanSnapshot;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 不可变计划快照视图。
 */
public record SnapshotResponse(
        String snapshotId,
        String requestId,
        int versionAtPublish,
        String statusAtPublish,
        JsonNode content,
        Instant publishedAt
) {

    public static SnapshotResponse from(PlanSnapshot snapshot, JsonNode parsedContent) {
        return new SnapshotResponse(
                snapshot.getSnapshotId(),
                snapshot.getRequestId(),
                snapshot.getVersionAtPublish(),
                snapshot.getStatusAtPublish().name(),
                parsedContent,
                Instant.ofEpochMilli(snapshot.getPublishedAt())
        );
    }
}
