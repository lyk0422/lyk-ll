package com.example.starter.ct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.ct.repo.CtLeafRepository;

/**
 * 日志核心服务测试：顺序索引、幂等、409 冲突、稳定错误、H2 唯一约束与真实并发。
 */
@SpringBootTest
class CtLogServiceTest {

    @Autowired
    private CtLogService ctLogService;

    @Autowired
    private CtLeafRepository leafRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM split_view_alert");
        jdbc.update("DELETE FROM observer_report");
        jdbc.update("DELETE FROM observer_state");
        jdbc.update("DELETE FROM ct_leaf");
    }

    private static byte[] der(String name) {
        return name.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void appendAssignsSequentialIndicesFromZero() {
        var first = ctLogService.append("req-1", der("cert-1"));
        var second = ctLogService.append("req-2", der("cert-2"));
        var third = ctLogService.append("req-3", der("cert-3"));
        assertEquals(0, first.leafIndex());
        assertEquals(1, second.leafIndex());
        assertEquals(2, third.leafIndex());
        assertEquals(3, third.treeSize());
        assertFalse(first.idempotent());
        assertEquals(3, ctLogService.currentTreeSize());
    }

    @Test
    void sameRequestIdSameEntryReturnsFirstIndex() {
        var first = ctLogService.append("req-idem", der("cert-x"));
        var replay = ctLogService.append("req-idem", der("cert-x"));
        assertEquals(first.leafIndex(), replay.leafIndex());
        assertEquals(first.leafHash(), replay.leafHash());
        assertTrue(replay.idempotent());
        assertEquals(1, ctLogService.currentTreeSize());
    }

    @Test
    void sameRequestIdDifferentEntryReturns409() {
        ctLogService.append("req-conflict", der("cert-a"));
        CtException ex = assertThrows(CtException.class,
                () -> ctLogService.append("req-conflict", der("cert-b")));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("REQUEST_ID_CONFLICT", ex.code());
        assertEquals(1, ctLogService.currentTreeSize());
    }

    @Test
    void concurrentAppendsGetUniqueConsecutiveIndices() throws Exception {
        int threads = 16;
        int perThread = 8;
        int total = threads * perThread;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> {
                gate.await();
                List<Long> indices = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    var result = ctLogService.append("req-" + threadId + "-" + i,
                            der("cert-" + threadId + "-" + i));
                    indices.add(result.leafIndex());
                }
                return indices;
            }));
        }
        gate.countDown();
        Set<Long> all = new HashSet<>();
        for (Future<List<Long>> future : futures) {
            all.addAll(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertEquals(total, all.size(), "叶索引必须唯一");
        for (long i = 0; i < total; i++) {
            assertTrue(all.contains(i), "缺少叶索引 " + i);
        }
        assertEquals(total, ctLogService.currentTreeSize());
    }

    @Test
    void concurrentDuplicateRequestIdYieldsSingleRow() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return ctLogService.append("req-dup", der("cert-dup")).leafIndex();
            }));
        }
        gate.countDown();
        Set<Long> indices = new HashSet<>();
        for (Future<Long> future : futures) {
            indices.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertEquals(1, indices.size());
        assertEquals(1, ctLogService.currentTreeSize());
    }

    @Test
    void h2UniqueConstraintRejectsDuplicateRequestIdAtDatabaseLevel() {
        ctLogService.append("req-db", der("cert-db"));
        var row = leafRepository.findByRequestId("req-db").orElseThrow();
        assertThrows(DuplicateKeyException.class, () -> leafRepository.insert(row.leafIndex() + 1, "req-db",
                row.entryHash(), row.leafHash(), row.createdAt()));
        assertThrows(DuplicateKeyException.class, () -> leafRepository.insert(row.leafIndex(), "req-other",
                row.entryHash(), row.leafHash(), row.createdAt()));
    }

    @Test
    void treeHeadRejectsZeroAndOutOfRangeSizes() {
        CtException empty = assertThrows(CtException.class, () -> ctLogService.treeHead(null));
        assertEquals("INVALID_TREE_SIZE", empty.code());

        ctLogService.append("req-th", der("cert-th"));
        assertEquals("INVALID_TREE_SIZE",
                assertThrows(CtException.class, () -> ctLogService.treeHead(0L)).code());
        assertEquals("INVALID_TREE_SIZE",
                assertThrows(CtException.class, () -> ctLogService.treeHead(2L)).code());
        assertEquals("INVALID_TREE_SIZE",
                assertThrows(CtException.class, () -> ctLogService.treeHead(-1L)).code());
    }

    @Test
    void treeHeadRootMatchesCommittedPrefix() {
        for (int i = 0; i < 7; i++) {
            ctLogService.append("req-p" + i, der("cert-p" + i));
        }
        List<byte[]> hashes = leafRepository.findLeafHashesUpTo(7).stream()
                .map(java.util.HexFormat.of()::parseHex).toList();
        for (int size = 1; size <= 7; size++) {
            var head = ctLogService.treeHead((long) size);
            assertEquals(size, head.treeSize());
            assertEquals(java.util.HexFormat.of().formatHex(MerkleTree.rootHash(hashes, 0, size)), head.rootHash());
        }
    }

    @Test
    void inclusionProofRejectsOutOfRangeIndex() {
        ctLogService.append("req-ip", der("cert-ip"));
        assertEquals("LEAF_INDEX_OUT_OF_RANGE",
                assertThrows(CtException.class, () -> ctLogService.inclusionProof(1, null)).code());
        assertEquals("LEAF_INDEX_OUT_OF_RANGE",
                assertThrows(CtException.class, () -> ctLogService.inclusionProof(-1, null)).code());
        assertEquals("INVALID_TREE_SIZE",
                assertThrows(CtException.class, () -> ctLogService.inclusionProof(0, 0L)).code());
    }

    @Test
    void consistencyProofRejectsInvalidRanges() {
        for (int i = 0; i < 3; i++) {
            ctLogService.append("req-c" + i, der("cert-c" + i));
        }
        assertEquals("INVALID_TREE_SIZE_RANGE",
                assertThrows(CtException.class, () -> ctLogService.consistencyProof(0, 3)).code());
        assertEquals("INVALID_TREE_SIZE_RANGE",
                assertThrows(CtException.class, () -> ctLogService.consistencyProof(3, 2)).code());
        assertEquals("INVALID_TREE_SIZE_RANGE",
                assertThrows(CtException.class, () -> ctLogService.consistencyProof(1, 4)).code());
        assertEquals(0, ctLogService.consistencyProof(3, 3).consistencyPath().size());
    }

    @Test
    void inclusionProofVerifiesAgainstTreeHead() {
        for (int i = 0; i < 9; i++) {
            ctLogService.append("req-v" + i, der("cert-v" + i));
        }
        var head = ctLogService.treeHead(null);
        var proof = ctLogService.inclusionProof(5, null);
        byte[] computed = MerkleTree.verifyInclusion(java.util.HexFormat.of().parseHex(proof.leafHash()), 5, 9,
                proof.auditPath().stream().map(java.util.HexFormat.of()::parseHex).toList());
        assertEquals(head.rootHash(), java.util.HexFormat.of().formatHex(computed));
    }
}
