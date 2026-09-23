package com.example.starter.ct;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Merkle 哈希已知答案向量测试：常量取自公开的 SHA-256 标准向量。
 */
class BootstrapVectorTest {

    private static final HexFormat HEX = HexFormat.of();

    /** SHA-256(空) 标准向量。 */
    private static final String SHA256_EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** SHA-256(0x00) 标准向量，即 CT 中叶哈希 HashLeaf("") 的公开向量。 */
    private static final String SHA256_ZERO_BYTE = "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d";

    /** SHA-256("abc") 标准向量（NIST）。 */
    private static final String SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void leafHashOfEmptyMatchesKnownVector() {
        assertEquals(SHA256_ZERO_BYTE, HEX.formatHex(MerkleTree.leafHash(new byte[0])));
    }

    @Test
    void leafHashPrefixesZeroByte() throws Exception {
        // leafHash(x) 必须等于 SHA-256(0x00 || x)，用 JDK 独立重算对照。
        byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals(SHA256_ABC, HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((byte) 0x00);
        byte[] expected = digest.digest(payload);
        assertArrayEquals(expected, MerkleTree.leafHash(payload));
    }

    @Test
    void internalHashPrefixesOneByte() throws Exception {
        byte[] left = HEX.parseHex(SHA256_EMPTY);
        byte[] right = HEX.parseHex(SHA256_ZERO_BYTE);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((byte) 0x01);
        digest.update(left);
        byte[] expected = digest.digest(right);
        assertArrayEquals(expected, MerkleTree.internalHash(left, right));
    }

    @Test
    void singleLeafRootEqualsLeafHash() {
        byte[] leaf = MerkleTree.leafHash("only".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(leaf, MerkleTree.rootHash(List.of(leaf), 0, 1));
    }

    @Test
    void twoLeafRootMatchesManualComputation() throws Exception {
        byte[] l0 = MerkleTree.leafHash(new byte[] {0x10});
        byte[] l1 = MerkleTree.leafHash(new byte[] {0x11});
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((byte) 0x01);
        digest.update(l0);
        byte[] expected = digest.digest(l1);
        assertArrayEquals(expected, MerkleTree.rootHash(List.of(l0, l1), 0, 2));
    }

    @Test
    void oddLeafIsNotDuplicated() {
        // 3 叶时根 = H(0x01 || H(0x01||L0||L1) || L2)，奇数叶 L2 不复制。
        byte[] l0 = MerkleTree.leafHash(new byte[] {0x20});
        byte[] l1 = MerkleTree.leafHash(new byte[] {0x21});
        byte[] l2 = MerkleTree.leafHash(new byte[] {0x22});
        byte[] expected = MerkleTree.internalHash(MerkleTree.internalHash(l0, l1), l2);
        assertArrayEquals(expected, MerkleTree.rootHash(List.of(l0, l1, l2), 0, 3));

        // 若错误地复制奇数叶，结果必然不同。
        byte[] duplicated = MerkleTree.internalHash(MerkleTree.internalHash(l0, l1), MerkleTree.internalHash(l2, l2));
        org.junit.jupiter.api.Assertions.assertNotEquals(HEX.formatHex(duplicated), HEX.formatHex(expected));
    }
}
