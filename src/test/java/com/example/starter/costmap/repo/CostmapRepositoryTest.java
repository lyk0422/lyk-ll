package com.example.starter.costmap.repo;

import com.example.starter.costmap.dto.SnapshotView;
import com.example.starter.costmap.model.ObstacleRecord;
import com.example.starter.costmap.model.Region;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2（MySQL 兼容模式）数据访问测试：唯一约束、BLOB 往返与事务回滚边界。
 */
@SpringBootTest
class CostmapRepositoryTest {

    @Autowired
    private SnapshotRepository snapshots;

    @Autowired
    private ObstacleRequestRepository requests;

    @Autowired
    private ObstacleUpdateRepository updates;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM obstacle_update");
        jdbc.update("DELETE FROM obstacle_request");
        jdbc.update("DELETE FROM costmap_snapshot");
    }

    private String mapId() {
        return "repo-" + UUID.randomUUID();
    }

    @Test
    void snapshotRoundTripsGridBytes() {
        String mapId = mapId();
        byte[] grid = new byte[]{0, 1, (byte) 254, (byte) 255, 127};
        snapshots.insert(mapId, 3, 5, 1, 0.5, 1.5, grid, Instant.parse("2026-09-23T00:00:00Z"));

        SnapshotView view = snapshots.find(mapId, 3).orElseThrow();
        assertEquals(mapId, view.mapId());
        assertEquals(3, view.version());
        assertEquals(5, view.width());
        assertEquals(1, view.height());
        assertEquals(0.5, view.resolution());
        assertEquals(1.5, view.inflationRadius());
        assertArrayEquals(new int[]{0, 1, 254, 255, 127}, view.grid());
        assertEquals("2026-09-23T00:00:00Z", view.createdAt());
        assertTrue(snapshots.find(mapId, 4).isEmpty());
    }

    @Test
    void snapshotPrimaryKeyRejectsDuplicatePublish() {
        String mapId = mapId();
        byte[] grid = new byte[]{0};
        snapshots.insert(mapId, 0, 1, 1, 1.0, 0.0, grid, Instant.now());
        assertThrows(DuplicateKeyException.class, () ->
                snapshots.insert(mapId, 0, 1, 1, 1.0, 0.0, grid, Instant.now()));
    }

    @Test
    void obstacleRequestPrimaryKeyEnforcesIdempotency() {
        String mapId = mapId();
        requests.insert(mapId, "req-1", "hash-a", 7, Instant.now());
        Optional<ObstacleRequestRepository.Record> found = requests.find(mapId, "req-1");
        assertTrue(found.isPresent());
        assertEquals("hash-a", found.get().requestHash());
        assertEquals(7, found.get().appliedVersion());
        assertThrows(DuplicateKeyException.class, () ->
                requests.insert(mapId, "req-1", "hash-b", 8, Instant.now()));
    }

    @Test
    void transactionRollbackLeavesNoPartialWrites() {
        String mapId = mapId();
        ObstacleRecord record = new ObstacleRecord("obs-1", "req-1", "lidar", 0, null,
                new Region(0, 0, 1, 1), new byte[]{(byte) 254}, 1000L, null);
        assertThrows(IllegalStateException.class, () ->
                transactions.execute(status -> {
                    requests.insert(mapId, "req-1", "hash-a", 1, Instant.now());
                    updates.insert(mapId, record, 1, Instant.now());
                    throw new IllegalStateException("force rollback");
                }));
        assertTrue(requests.find(mapId, "req-1").isEmpty());
        assertEquals(0, updates.count(mapId));
    }

    @Test
    void transactionCommitPersistsAllWrites() {
        String mapId = mapId();
        ObstacleRecord record = new ObstacleRecord("obs-1", "req-1", "lidar", 0, null,
                new Region(0, 0, 1, 1), new byte[]{(byte) 254}, 1000L, 2000L);
        transactions.executeWithoutResult(status -> {
            requests.insert(mapId, "req-1", "hash-a", 1, Instant.now());
            updates.insert(mapId, record, 1, Instant.now());
        });
        assertTrue(requests.find(mapId, "req-1").isPresent());
        assertEquals(1, updates.count(mapId));
        Long expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM obstacle_update WHERE map_id = ?", Long.class, mapId);
        assertEquals(2000L, expiresAt);
    }
}
