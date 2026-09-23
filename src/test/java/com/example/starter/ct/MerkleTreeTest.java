package com.example.starter.ct;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Merkle 树证明生成与验证测试：与测试内独立朴素实现交叉校验，覆盖正反例。
 */
class MerkleTreeTest {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * 测试内独立朴素根计算：同一规范、不同代码路径。
     */
    private static byte[] naiveRoot(List<byte[]> leaves, int from, int to) throws Exception {
        int n = to - from;
        if (n == 1) {
            return leaves.get(from);
        }
        int k = 1;
        while (k * 2 < n) {
            k *= 2;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((byte) 0x01);
        digest.update(naiveRoot(leaves, from, from + k));
        return digest.digest(naiveRoot(leaves, from + k, to));
    }

    private static List<byte[]> leaves(int count) {
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            leaves.add(MerkleTree.leafHash(("leaf-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        return leaves;
    }

    @Test
    void rootMatchesNaiveImplementationForSizes1To20() throws Exception {
        for (int n = 1; n <= 20; n++) {
            List<byte[]> leaves = leaves(n);
            assertArrayEquals(naiveRoot(leaves, 0, n), MerkleTree.rootHash(leaves, 0, n),
                    "root mismatch at size " + n);
        }
    }

    @Test
    void inclusionProofVerifiesForAllIndicesAndSizes() throws Exception {
        for (int n = 1; n <= 20; n++) {
            List<byte[]> leaves = leaves(n);
            byte[] root = naiveRoot(leaves, 0, n);
            for (int i = 0; i < n; i++) {
                List<byte[]> path = MerkleTree.auditPath(leaves, i);
                byte[] computed = MerkleTree.verifyInclusion(leaves.get(i), i, n, path);
                assertArrayEquals(root, computed, "inclusion proof failed at index " + i + " of " + n);
            }
        }
    }

    @Test
    void inclusionProofTamperingIsDetected() throws Exception {
        List<byte[]> leaves = leaves(7);
        byte[] root = naiveRoot(leaves, 0, 7);
        List<byte[]> path = MerkleTree.auditPath(leaves, 3);

        // 篡改路径元素。
        List<byte[]> tamperedPath = new ArrayList<>(path);
        byte[] evil = tamperedPath.get(0).clone();
        evil[0] ^= 0x01;
        tamperedPath.set(0, evil);
        assertNotEquals(HEX.formatHex(root),
                HEX.formatHex(MerkleTree.verifyInclusion(leaves.get(3), 3, 7, tamperedPath)));

        // 错误的叶索引。
        assertNotEquals(HEX.formatHex(root),
                HEX.formatHex(MerkleTree.verifyInclusion(leaves.get(3), 2, 7, path)));

        // 错误的叶哈希。
        assertNotEquals(HEX.formatHex(root),
                HEX.formatHex(MerkleTree.verifyInclusion(leaves.get(2), 3, 7, path)));

        // 越界参数直接拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> MerkleTree.verifyInclusion(leaves.get(0), 7, 7, path));
        assertThrows(IllegalArgumentException.class,
                () -> MerkleTree.verifyInclusion(leaves.get(0), 0, 0, List.of()));
    }

    @Test
    void inclusionProofStructureForSmallTrees() {
        List<byte[]> one = leaves(1);
        assertTrue(MerkleTree.auditPath(one, 0).isEmpty());

        List<byte[]> two = leaves(2);
        assertEquals(List.of(two.get(1)), MerkleTree.auditPath(two, 0));
        assertEquals(List.of(two.get(0)), MerkleTree.auditPath(two, 1));
    }

    @Test
    void consistencyProofVerifiesForAllRanges() throws Exception {
        for (int n = 2; n <= 16; n++) {
            List<byte[]> leaves = leaves(n);
            byte[] rootN = naiveRoot(leaves, 0, n);
            for (int m = 1; m < n; m++) {
                byte[] rootM = naiveRoot(leaves, 0, m);
                List<byte[]> path = MerkleTree.consistencyPath(leaves, m, n);
                assertTrue(MerkleTree.verifyConsistency(m, n, rootM, rootN, path),
                        "consistency proof failed for " + m + " -> " + n);
            }
        }
    }

    @Test
    void consistencyProofTamperingIsDetected() throws Exception {
        List<byte[]> leaves = leaves(9);
        byte[] root4 = naiveRoot(leaves, 0, 4);
        byte[] root9 = naiveRoot(leaves, 0, 9);
        List<byte[]> path = MerkleTree.consistencyPath(leaves, 4, 9);
        assertFalse(path.isEmpty());

        // 篡改路径元素。
        List<byte[]> tampered = new ArrayList<>(path);
        byte[] evil = tampered.get(0).clone();
        evil[0] ^= 0x01;
        tampered.set(0, evil);
        assertFalse(MerkleTree.verifyConsistency(4, 9, root4, root9, tampered));

        // 错误的旧根。
        byte[] wrongOld = root4.clone();
        wrongOld[0] ^= 0x01;
        assertFalse(MerkleTree.verifyConsistency(4, 9, wrongOld, root9, path));

        // 错误的新根。
        assertFalse(MerkleTree.verifyConsistency(4, 9, root4, root4, path));

        // 截断路径。
        assertFalse(MerkleTree.verifyConsistency(4, 9, root4, root9, path.subList(0, path.size() - 1)));

        // 非法大小范围。
        assertFalse(MerkleTree.verifyConsistency(0, 9, root4, root9, path));
        assertFalse(MerkleTree.verifyConsistency(9, 4, root9, root4, path));
    }

    @Test
    void consistencyProofForEqualSizesIsEmpty() throws Exception {
        List<byte[]> leaves = leaves(5);
        assertTrue(MerkleTree.consistencyPath(leaves, 5, 5).isEmpty());
        byte[] root = naiveRoot(leaves, 0, 5);
        assertTrue(MerkleTree.verifyConsistency(5, 5, root, root, List.of()));
        assertFalse(MerkleTree.verifyConsistency(5, 5, root, MerkleTree.leafHash(new byte[] {1}), List.of()));
    }

    @Test
    void consistencyProofStructureForTwoToThree() {
        // SUBPROOF(2, D[3], true) = [MTH(D[2:3])] = [L2]。
        List<byte[]> leaves = leaves(3);
        List<byte[]> path = MerkleTree.consistencyPath(leaves, 2, 3);
        assertEquals(1, path.size());
        assertArrayEquals(leaves.get(2), path.get(0));
    }

    @Test
    void oddSizedSubtreeRootsDoNotDuplicateLastNode() throws Exception {
        // 5 叶：k=4，根 = H(0x01 || root(0..4) || L4)，L4 不复制。
        List<byte[]> leaves = leaves(5);
        byte[] expected = MerkleTree.internalHash(naiveRoot(leaves, 0, 4), leaves.get(4));
        assertArrayEquals(expected, MerkleTree.rootHash(leaves, 0, 5));
        byte[] duplicated = MerkleTree.internalHash(naiveRoot(leaves, 0, 4),
                MerkleTree.internalHash(leaves.get(4), leaves.get(4)));
        assertFalse(Arrays.equals(expected, duplicated));
    }
}
