package com.example.starter.ctlog;

import com.example.starter.ctlog.dto.AddLeafRequest;
import com.example.starter.ctlog.dto.AddLeafResponse;
import com.example.starter.ctlog.dto.ConsistencyProofResponse;
import com.example.starter.ctlog.dto.InclusionProofResponse;
import com.example.starter.ctlog.dto.TreeHeadResponse;
import com.example.starter.ctlog.merkle.MerkleTree;
import com.example.starter.ctlog.repo.CtLogRepository;
import com.example.starter.ctlog.service.CtLogService;
import com.example.starter.ctlog.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志追加、树头与证明的 H2 集成测试，含并发索引分配与幂等边界。
 */
class CtLogServiceTest extends AbstractCtLogIntegrationTest {

    @Autowired
    private CtLogService service;

    @Autowired
    private CtLogRepository repository;

    private static AddLeafRequest leafRequest(String requestId, int seed) {
        return new AddLeafRequest(requestId,
                Base64.getEncoder().encodeToString(TestSigning.syntheticDer(seed)));
    }

    @Test
    void sequentialAppendsGetContiguousIndexes() {
        AddLeafResponse r0 = service.addLeaf(leafRequest("req-0", 0));
        AddLeafResponse r1 = service.addLeaf(leafRequest("req-1", 1));
        AddLeafResponse r2 = service.addLeaf(leafRequest("req-2", 2));
        assertEquals(0L, r0.leafIndex());
        assertEquals(1L, r1.leafIndex());
        assertEquals(2L, r2.leafIndex());
        assertFalse(r0.idempotent());
        assertEquals(64, r0.entryHash().length());
        assertEquals(3L, repository.currentSize());
    }

    @Test
    void sameRequestIdSameEntryReturnsFirstIndex() {
        AddLeafResponse first = service.addLeaf(leafRequest("dup-1", 7));
        AddLeafResponse replay = service.addLeaf(leafRequest("dup-1", 7));
        assertEquals(first.leafIndex(), replay.leafIndex());
        assertEquals(first.entryHash(), replay.entryHash());
        assertTrue(replay.idempotent());
        assertEquals(1L, repository.currentSize());
    }

    @Test
    void sameRequestIdDifferentEntryReturnsConflict() {
        service.addLeaf(leafRequest("dup-2", 1));
        ApiException ex = assertThrows(ApiException.class, () -> service.addLeaf(leafRequest("dup-2", 2)));
        assertEquals("REQUEST_ID_CONFLICT", ex.getCode());
        assertEquals(409, ex.getHttpStatus().value());
        assertEquals(1L, repository.currentSize());
    }

    @Test
    void concurrentAppendsGetUniqueContiguousIndexes() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AddLeafResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int seed = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.addLeaf(leafRequest("conc-" + seed, seed));
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        Set<Long> indexes = new HashSet<>();
        for (Future<AddLeafResponse> future : futures) {
            indexes.add(future.get(30, TimeUnit.SECONDS).leafIndex());
        }
        pool.shutdownNow();
        assertEquals(threads, indexes.size());
        for (long i = 0; i < threads; i++) {
            assertTrue(indexes.contains(i), "missing index " + i);
        }
        assertEquals(threads, repository.currentSize());
    }

    @Test
    void concurrentSameRequestIdYieldsSingleLeaf() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AddLeafResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.addLeaf(leafRequest("idem-conc", 42));
            }));
        }
        start.countDown();
        Set<Long> indexes = new HashSet<>();
        int fresh = 0;
        for (Future<AddLeafResponse> future : futures) {
            AddLeafResponse response = future.get(30, TimeUnit.SECONDS);
            indexes.add(response.leafIndex());
            if (!response.idempotent()) {
                fresh++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, indexes.size());
        assertEquals(1, fresh);
        assertEquals(1L, repository.currentSize());
    }

    @Test
    void treeHeadMatchesCommittedPrefix() {
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AddLeafResponse response = service.addLeaf(leafRequest("th-" + i, i));
            leaves.add(HexFormat.of().parseHex(response.entryHash()));
        }
        TreeHeadResponse head = service.getTreeHead(null);
        assertEquals(5L, head.treeSize());
        assertEquals(HexFormat.of().formatHex(MerkleTree.root(leaves)), head.rootHash());

        TreeHeadResponse prefix = service.getTreeHead(3L);
        assertEquals(3L, prefix.treeSize());
        assertEquals(HexFormat.of().formatHex(MerkleTree.root(leaves.subList(0, 3))), prefix.rootHash());
    }

    @Test
    void treeHeadRejectsZeroAndOversize() {
        service.addLeaf(leafRequest("th-err", 1));
        ApiException zero = assertThrows(ApiException.class, () -> service.getTreeHead(0L));
        assertEquals("INVALID_TREE_SIZE", zero.getCode());
        ApiException over = assertThrows(ApiException.class, () -> service.getTreeHead(2L));
        assertEquals("INVALID_TREE_SIZE", over.getCode());
    }

    @Test
    void treeHeadOnEmptyLogIsRejected() {
        ApiException empty = assertThrows(ApiException.class, () -> service.getTreeHead(null));
        assertEquals("INVALID_TREE_SIZE", empty.getCode());
    }

    @Test
    void inclusionProofVerifiesLocally() {
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            AddLeafResponse response = service.addLeaf(leafRequest("inc-" + i, i));
            leaves.add(HexFormat.of().parseHex(response.entryHash()));
        }
        InclusionProofResponse proof = service.inclusionProof(4, null);
        assertEquals(4L, proof.leafIndex());
        assertEquals(7L, proof.treeSize());
        List<byte[]> path = proof.proof().stream().map(HexFormat.of()::parseHex).toList();
        assertTrue(MerkleTree.verifyInclusion(HexFormat.of().parseHex(proof.leafHash()),
                4, 7, path, HexFormat.of().parseHex(proof.rootHash())));
        assertEquals(HexFormat.of().formatHex(MerkleTree.root(leaves)), proof.rootHash());
    }

    @Test
    void inclusionProofRejectsOutOfRangeIndex() {
        service.addLeaf(leafRequest("inc-err", 1));
        ApiException ex = assertThrows(ApiException.class, () -> service.inclusionProof(1, null));
        assertEquals("LEAF_INDEX_OUT_OF_RANGE", ex.getCode());
        ApiException neg = assertThrows(ApiException.class, () -> service.inclusionProof(-1, null));
        assertEquals("LEAF_INDEX_OUT_OF_RANGE", neg.getCode());
    }

    @Test
    void consistencyProofVerifiesLocally() {
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            AddLeafResponse response = service.addLeaf(leafRequest("con-" + i, i));
            leaves.add(HexFormat.of().parseHex(response.entryHash()));
        }
        ConsistencyProofResponse proof = service.consistencyProof(3, null);
        assertEquals(3L, proof.firstTreeSize());
        assertEquals(6L, proof.secondTreeSize());
        List<byte[]> path = proof.proof().stream().map(HexFormat.of()::parseHex).toList();
        assertTrue(MerkleTree.verifyConsistency(3, 6,
                HexFormat.of().parseHex(proof.firstRootHash()),
                HexFormat.of().parseHex(proof.secondRootHash()), path));

        ConsistencyProofResponse same = service.consistencyProof(6, 6L);
        assertTrue(same.proof().isEmpty());
        assertEquals(same.firstRootHash(), same.secondRootHash());
    }

    @Test
    void consistencyProofRejectsInvalidRanges() {
        service.addLeaf(leafRequest("con-err", 1));
        ApiException zeroFirst = assertThrows(ApiException.class,
                () -> service.consistencyProof(0, null));
        assertEquals("INVALID_TREE_SIZE", zeroFirst.getCode());
        ApiException reversed = assertThrows(ApiException.class,
                () -> service.consistencyProof(2, 1L));
        assertEquals("INVALID_TREE_RANGE", reversed.getCode());
    }

    @Test
    void concurrentAppendsAndTreeHeadReadsStayOnCommittedPrefixes() throws Exception {
        int appends = 40;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> writer = pool.submit(() -> {
            try {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                for (int i = 0; i < appends; i++) {
                    service.addLeaf(leafRequest("rw-" + i, i));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        List<Future<?>> readers = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            readers.add(pool.submit(() -> {
                try {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    for (int i = 0; i < appends; i++) {
                        try {
                            TreeHeadResponse head = service.getTreeHead(null);
                            // 读到的每个树头都必须等于对应已提交前缀重新计算的根。
                            byte[] expected = MerkleTree.root(
                                    repository.leafHashesBetween(0, head.treeSize()));
                            assertEquals(HexFormat.of().formatHex(expected), head.rootHash());
                        } catch (ApiException empty) {
                            assertEquals("INVALID_TREE_SIZE", empty.getCode());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        writer.get(60, TimeUnit.SECONDS);
        for (Future<?> reader : readers) {
            reader.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(appends, repository.currentSize());
        TreeHeadResponse head = service.getTreeHead(null);
        assertEquals(appends, head.treeSize());
    }
}
