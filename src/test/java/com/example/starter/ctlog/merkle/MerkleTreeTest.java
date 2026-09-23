package com.example.starter.ctlog.merkle;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Merkle 树哈希规则与证明的单元测试。
 */
class MerkleTreeTest {

    private static final HexFormat HEX = HexFormat.of();

    private static byte[] sha256(byte... input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static List<byte[]> leaves(int n) {
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            leaves.add(new byte[]{(byte) (i + 1), (byte) (i * 7 + 3), (byte) (i * 13 + 5)});
        }
        return leaves;
    }

    @Test
    void emptyTreeRootIsSha256OfEmptyInput() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                HEX.formatHex(MerkleTree.root(List.of())));
    }

    @Test
    void leafHashUsesZeroPrefixVector() {
        // SHA-256(0x00) 的公开已知值。
        assertEquals("6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d",
                HEX.formatHex(MerkleTree.leafHash(new byte[0])));
    }

    @Test
    void singleLeafTreeRootEqualsLeafHash() {
        byte[] leaf = {0x01, 0x02, 0x03};
        assertEquals(HEX.formatHex(sha256(concat(new byte[]{0x00}, leaf))),
                HEX.formatHex(MerkleTree.root(List.of(leaf))));
    }

    @Test
    void twoLeafTreeRootUsesInnerPrefix() {
        byte[] l0 = {0x0a};
        byte[] l1 = {0x0b};
        byte[] expected = sha256(concat(new byte[]{0x01},
                sha256(concat(new byte[]{0x00}, l0)),
                sha256(concat(new byte[]{0x00}, l1))));
        assertEquals(HEX.formatHex(expected), HEX.formatHex(MerkleTree.root(List.of(l0, l1))));
    }

    @Test
    void oddLeafIsNotDuplicated() {
        // 3 叶：root = H(0x01 || H(0x01 || h0 || h1) || h2)，第三叶不自我复制。
        List<byte[]> ls = leaves(3);
        byte[] h0 = sha256(concat(new byte[]{0x00}, ls.get(0)));
        byte[] h1 = sha256(concat(new byte[]{0x00}, ls.get(1)));
        byte[] h2 = sha256(concat(new byte[]{0x00}, ls.get(2)));
        byte[] expected = sha256(concat(new byte[]{0x01},
                sha256(concat(new byte[]{0x01}, h0, h1)), h2));
        assertEquals(HEX.formatHex(expected), HEX.formatHex(MerkleTree.root(ls)));
        // 若错误地复制奇数叶，根会不同。
        byte[] duplicated = sha256(concat(new byte[]{0x01},
                sha256(concat(new byte[]{0x01}, h0, h1)),
                sha256(concat(new byte[]{0x01}, h2, h2))));
        assertFalse(HEX.formatHex(duplicated).equals(HEX.formatHex(MerkleTree.root(ls))));
    }

    @Test
    void fiveLeafTreeMatchesHandRolledReference() {
        // 5 叶：k=4，root = H(0x01 || H(0x01 || H(0x01||h0||h1) || H(0x01||h2||h3)) || h4)。
        List<byte[]> ls = leaves(5);
        byte[][] h = new byte[5][];
        for (int i = 0; i < 5; i++) {
            h[i] = sha256(concat(new byte[]{0x00}, ls.get(i)));
        }
        byte[] n01 = sha256(concat(new byte[]{0x01}, h[0], h[1]));
        byte[] n23 = sha256(concat(new byte[]{0x01}, h[2], h[3]));
        byte[] n0123 = sha256(concat(new byte[]{0x01}, n01, n23));
        byte[] expected = sha256(concat(new byte[]{0x01}, n0123, h[4]));
        assertEquals(HEX.formatHex(expected), HEX.formatHex(MerkleTree.root(ls)));
    }

    @Test
    void inclusionProofVerifiesForAllIndexesAndSizes() {
        for (int size = 1; size <= 9; size++) {
            List<byte[]> ls = leaves(size);
            byte[] root = MerkleTree.root(ls);
            for (int index = 0; index < size; index++) {
                List<byte[]> proof = MerkleTree.inclusionProof(ls, index);
                assertTrue(MerkleTree.verifyInclusion(
                        MerkleTree.leafHash(ls.get(index)), index, size, proof, root),
                        "size=" + size + " index=" + index);
            }
        }
    }

    @Test
    void inclusionProofRejectsTampering() {
        List<byte[]> ls = leaves(6);
        byte[] root = MerkleTree.root(ls);
        List<byte[]> proof = MerkleTree.inclusionProof(ls, 3);

        // 篡改证明节点。
        List<byte[]> tampered = new ArrayList<>(proof);
        byte[] node = tampered.get(0).clone();
        node[0] ^= 0x01;
        tampered.set(0, node);
        assertFalse(MerkleTree.verifyInclusion(MerkleTree.leafHash(ls.get(3)), 3, 6, tampered, root));

        // 错误叶数据。
        assertFalse(MerkleTree.verifyInclusion(MerkleTree.leafHash(new byte[]{0x7f}), 3, 6, proof, root));

        // 错误叶索引。
        assertFalse(MerkleTree.verifyInclusion(MerkleTree.leafHash(ls.get(3)), 4, 6, proof, root));

        // 越界索引直接判负。
        assertFalse(MerkleTree.verifyInclusion(MerkleTree.leafHash(ls.get(3)), 6, 6, proof, root));
        assertFalse(MerkleTree.verifyInclusion(MerkleTree.leafHash(ls.get(3)), 3, 0, proof, root));
    }

    @Test
    void inclusionProofRejectsOutOfRangeGeneration() {
        List<byte[]> ls = leaves(2);
        assertThrows(IllegalArgumentException.class, () -> MerkleTree.inclusionProof(ls, 2));
        assertThrows(IllegalArgumentException.class, () -> MerkleTree.inclusionProof(ls, -1));
    }

    @Test
    void consistencyProofVerifiesForAllPairs() {
        List<byte[]> ls = leaves(9);
        for (int second = 1; second <= 9; second++) {
            byte[] secondRoot = MerkleTree.root(ls.subList(0, second));
            for (int first = 1; first <= second; first++) {
                byte[] firstRoot = MerkleTree.root(ls.subList(0, first));
                List<byte[]> proof = first == second
                        ? List.of()
                        : MerkleTree.consistencyProof(ls, first, second);
                assertTrue(MerkleTree.verifyConsistency(first, second, firstRoot, secondRoot, proof),
                        "first=" + first + " second=" + second);
            }
        }
    }

    @Test
    void consistencyProofRejectsTampering() {
        List<byte[]> ls = leaves(7);
        byte[] firstRoot = MerkleTree.root(ls.subList(0, 3));
        byte[] secondRoot = MerkleTree.root(ls);
        List<byte[]> proof = MerkleTree.consistencyProof(ls, 3, 7);

        // 篡改证明节点。
        List<byte[]> tampered = new ArrayList<>(proof);
        byte[] node = tampered.get(0).clone();
        node[0] ^= 0x01;
        tampered.set(0, node);
        assertFalse(MerkleTree.verifyConsistency(3, 7, firstRoot, secondRoot, tampered));

        // 交换新旧根。
        assertFalse(MerkleTree.verifyConsistency(3, 7, secondRoot, firstRoot, proof));

        // 非法大小组合。
        assertFalse(MerkleTree.verifyConsistency(0, 7, firstRoot, secondRoot, proof));
        assertFalse(MerkleTree.verifyConsistency(8, 7, firstRoot, secondRoot, proof));
        assertThrows(IllegalArgumentException.class, () -> MerkleTree.consistencyProof(ls, 0, 7));
        assertThrows(IllegalArgumentException.class, () -> MerkleTree.consistencyProof(ls, 8, 7));
    }
}
