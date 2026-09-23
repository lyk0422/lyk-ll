package com.example.starter.ctlog.merkle;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 6962 风格的 Merkle 树工具。
 *
 * <p>哈希规则固定为：叶节点 {@code SHA-256(0x00 || leafBytes)}，
 * 内部节点 {@code SHA-256(0x01 || left || right)}；递归分割时奇数节点不复制，
 * 分割点为严格小于子树大小的最大 2 的幂。</p>
 */
public final class MerkleTree {

    private static final byte LEAF_PREFIX = 0x00;
    private static final byte INNER_PREFIX = 0x01;

    private MerkleTree() {
    }

    /**
     * 计算叶节点哈希：{@code SHA-256(0x00 || leafBytes)}。
     */
    public static byte[] leafHash(byte[] leafBytes) {
        MessageDigest digest = sha256();
        digest.update(LEAF_PREFIX);
        return digest.digest(leafBytes);
    }

    /**
     * 计算内部节点哈希：{@code SHA-256(0x01 || left || right)}。
     */
    public static byte[] innerHash(byte[] left, byte[] right) {
        MessageDigest digest = sha256();
        digest.update(INNER_PREFIX);
        digest.update(left);
        return digest.digest(right);
    }

    /**
     * 计算包含 {@code leaves} 的 Merkle 树根；空树返回 SHA-256 空输入摘要。
     */
    public static byte[] root(List<byte[]> leaves) {
        if (leaves.isEmpty()) {
            return sha256().digest();
        }
        return subtreeRoot(leaves, 0, leaves.size());
    }

    /**
     * 生成叶 {@code leafIndex} 在大小为 {@code leaves.size()} 的树中的包含证明（审计路径）。
     */
    public static List<byte[]> inclusionProof(List<byte[]> leaves, int leafIndex) {
        if (leafIndex < 0 || leafIndex >= leaves.size()) {
            throw new IllegalArgumentException("leafIndex out of range");
        }
        List<byte[]> path = new ArrayList<>();
        buildInclusionProof(leaves, 0, leaves.size(), leafIndex, path);
        return path;
    }

    /**
     * 生成大小为 {@code first} 的前缀与大小为 {@code second} 的树之间的一致性证明。
     * 要求 {@code 0 < first <= second <= leaves.size()}。
     */
    public static List<byte[]> consistencyProof(List<byte[]> leaves, int first, int second) {
        if (first <= 0 || first > second || second > leaves.size()) {
            throw new IllegalArgumentException("invalid first/second tree sizes");
        }
        return buildConsistencySubproof(leaves, 0, second, first, true);
    }

    /**
     * 本地验证包含证明：由叶哈希与审计路径重算根并与 {@code expectedRoot} 比较。
     */
    public static boolean verifyInclusion(byte[] leafHash, long leafIndex, long treeSize,
                                          List<byte[]> proof, byte[] expectedRoot) {
        if (leafIndex < 0 || treeSize <= 0 || leafIndex >= treeSize || expectedRoot == null) {
            return false;
        }
        long fn = leafIndex;
        long sn = treeSize - 1;
        byte[] r = leafHash;
        for (byte[] p : proof) {
            if ((fn & 1L) == 1L || fn == sn) {
                r = innerHash(p, r);
                while ((fn & 1L) == 0L && fn != 0L) {
                    fn >>= 1;
                    sn >>= 1;
                }
            } else {
                r = innerHash(r, p);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return sn == 0 && MessageDigest.isEqual(r, expectedRoot);
    }

    /**
     * 本地验证一致性证明：由审计路径同时重算新旧两个根并比较。
     */
    public static boolean verifyConsistency(long first, long second, byte[] firstRoot, byte[] secondRoot,
                                            List<byte[]> proof) {
        if (first <= 0 || first > second || firstRoot == null || secondRoot == null) {
            return false;
        }
        if (first == second) {
            return proof.isEmpty() && MessageDigest.isEqual(firstRoot, secondRoot);
        }
        List<byte[]> path = new ArrayList<>(proof);
        if ((first & (first - 1)) == 0) {
            // first 为 2 的幂（含 1）时，firstRoot 本身即路径首元素。
            path.add(0, firstRoot);
        }
        long fn = first - 1;
        long sn = second - 1;
        if ((fn & 1L) == 1L) {
            do {
                fn >>= 1;
                sn >>= 1;
            } while ((fn & 1L) == 1L);
        }
        if (path.isEmpty()) {
            return first == second && MessageDigest.isEqual(firstRoot, secondRoot);
        }
        byte[] fr = path.get(0);
        byte[] sr = path.get(0);
        for (int i = 1; i < path.size(); i++) {
            byte[] c = path.get(i);
            if (sn == 0) {
                return false;
            }
            if ((fn & 1L) == 1L || fn == sn) {
                fr = innerHash(c, fr);
                sr = innerHash(c, sr);
                while ((fn & 1L) == 0L && fn != 0L) {
                    fn >>= 1;
                    sn >>= 1;
                }
            } else {
                sr = innerHash(sr, c);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return sn == 0
                && MessageDigest.isEqual(fr, firstRoot)
                && MessageDigest.isEqual(sr, secondRoot);
    }

    /**
     * 严格小于 {@code n} 的最大 2 的幂，要求 {@code n >= 2}。
     */
    static int largestPowerOfTwoLessThan(int n) {
        int k = 1;
        while (k < n) {
            k <<= 1;
        }
        return k >> 1;
    }

    private static byte[] subtreeRoot(List<byte[]> leaves, int start, int size) {
        if (size == 1) {
            return leafHash(leaves.get(start));
        }
        int k = largestPowerOfTwoLessThan(size);
        return innerHash(subtreeRoot(leaves, start, k), subtreeRoot(leaves, start + k, size - k));
    }

    private static void buildInclusionProof(List<byte[]> leaves, int start, int size,
                                            int leafIndex, List<byte[]> path) {
        if (size == 1) {
            return;
        }
        int k = largestPowerOfTwoLessThan(size);
        if (leafIndex < k) {
            buildInclusionProof(leaves, start, k, leafIndex, path);
            path.add(subtreeRoot(leaves, start + k, size - k));
        } else {
            buildInclusionProof(leaves, start + k, size - k, leafIndex - k, path);
            path.add(subtreeRoot(leaves, start, k));
        }
    }

    private static List<byte[]> buildConsistencySubproof(List<byte[]> leaves, int start, int size,
                                                         int m, boolean exactSubtree) {
        List<byte[]> path = new ArrayList<>();
        if (m == size) {
            if (!exactSubtree) {
                path.add(subtreeRoot(leaves, start, size));
            }
            return path;
        }
        int k = largestPowerOfTwoLessThan(size);
        if (m <= k) {
            path.addAll(buildConsistencySubproof(leaves, start, k, m, exactSubtree));
            path.add(subtreeRoot(leaves, start + k, size - k));
        } else {
            path.addAll(buildConsistencySubproof(leaves, start + k, size - k, m - k, false));
            path.add(subtreeRoot(leaves, start, k));
        }
        return path;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
