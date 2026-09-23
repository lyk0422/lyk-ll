package com.example.starter.pointcloud.service;

import com.example.starter.pointcloud.model.BoundingBox;
import com.example.starter.pointcloud.model.ChunkMetadata;
import com.example.starter.pointcloud.model.Compression;
import com.example.starter.pointcloud.support.H2TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkIndexRepository} 的 H2（MODE=MySQL）测试。
 */
class ChunkIndexRepositoryTest {

    private H2TestSupport h2;
    private ChunkIndexRepository repository;

    @BeforeEach
    void setUp() {
        h2 = H2TestSupport.start();
        repository = new ChunkIndexRepository(h2.dataSource());
    }

    @AfterEach
    void tearDown() {
        h2.shutdown();
    }

    private ChunkMetadata chunk(String id, long start, long end, double minX, double maxX) {
        return new ChunkMetadata(id, 1L, "src-" + id, start, end,
                new BoundingBox(minX, 0, 0, maxX, 10, 10),
                2, Compression.NONE, checksum(id),
                id + ".pcc");
    }

    private static String checksum(String id) {
        return ("checksum-" + id + "0".repeat(64)).substring(0, 64);
    }

    @Test
    void upsertFindAndRoundTripAllFields() {
        ChunkMetadata m = chunk("a", 100, 200, 0, 5);
        repository.upsert(m, 1L);

        ChunkMetadata loaded = repository.find("a");
        assertEquals(m, loaded);
        assertEquals(1, repository.count());
    }

    @Test
    void upsertMergesByPrimaryKeyInsteadOfDuplicating() {
        repository.upsert(chunk("a", 100, 200, 0, 5), 1L);
        ChunkMetadata updated = new ChunkMetadata("a", 9L, "src-2", 150, 250,
                new BoundingBox(1, 1, 1, 6, 6, 6),
                3, Compression.GZIP,
                ("new-checksum" + "0".repeat(64)).substring(0, 64),
                "a2.pcc");
        repository.upsert(updated, 2L);

        assertEquals(1, repository.count(), "MERGE 不应产生重复主键行");
        assertEquals(updated, repository.find("a"));
    }

    @Test
    void findByTimeRangeUsesClosedIntervals() {
        repository.upsert(chunk("a", 100, 200, 0, 1), 1L);
        repository.upsert(chunk("b", 201, 300, 0, 1), 1L);
        repository.upsert(chunk("c", 50, 99, 0, 1), 1L);

        // 端点恰好相接（200 与 200）必须命中 a
        List<ChunkMetadata> touching = repository.findByTimeRange(200, 200);
        assertEquals(List.of("a"), touching.stream().map(ChunkMetadata::chunkId).toList());

        List<ChunkMetadata> range = repository.findByTimeRange(150, 250);
        List<String> ids = range.stream().map(ChunkMetadata::chunkId).sorted().toList();
        assertEquals(List.of("a", "b"), ids);
        assertTrue(range.stream().noneMatch(m -> m.chunkId().equals("c")));
    }

    @Test
    void deleteRemovesRow() {
        repository.upsert(chunk("a", 100, 200, 0, 5), 1L);
        repository.delete("a");
        assertNull(repository.find("a"));
        assertEquals(0, repository.count());
    }
}
