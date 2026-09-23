package com.example.starter.ct;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RFC 6962 风格的 Merkle 树哈希与证明工具（纯函数，无状态）。
 *
 * <p>哈希规则固定：叶节点 {@code SHA-256(0x00 || leafBytes)}，
 * 内部节点 {@code SHA-256(0x01 || left || right)}；奇数节点不复制，
 * 按标准递归分割（k 为小于 n 的最大 2 的幂）计算。</p>
 */
public final class MerkleTree {

    private MerkleTree() {
    }

    /**
     * 计算叶节点哈希：SHA-256(0x00 || leafBytes)。
     */
    public static byte[] leafHash(byte[] leafBytes) {
        MessageDigest digest = sha256();
        digest.update((byte) 0x00);
        return digest.digest(leafBytes);
    }

    /**
     * 计算内部节点哈希：SHA-256(0x01 || left || right)。
     */
    public static byte[] internalHash(byte[] left, byte[] right) {
        MessageDigest digest = sha256();
        digest.update((byte) 0x01);
        digest.update(left);
        return digest.digest(right);
    }

    /**
     * 计算叶哈希列表 [from, to) 区间的 Merkle 根；to - from 必须大于 0。
     */
    public static byte[] rootHash(List<byte[]> leafHashes, int from, int to) {
        int n = to - from;
        if (n <= 0) {
            throw new IllegalArgumentException("empty range");
        }
        if (n == 1) {
            return leafHashes.get(from);
        }
        int k = largestPowerOfTwoLessThan(n);
        return internalHash(rootHash(leafHashes, from, from + k), rootHash(leafHashes, from + k, to));
    }

    /**
     * 生成叶索引 index 在整棵树（大小为 leafHashes.size()）下的包含证明（审计路径）。
     */
    public static List<byte[]> auditPath(List<byte[]> leafHashes, int index) {
        int n = leafHashes.size();
        if (index < 0 || index >= n) {
            throw new IllegalArgumentException("leaf index out of range");
        }
        List<byte[]> path = new ArrayList<>();
        buildAuditPath(leafHashes, 0, n, index, path);
        return path;
    }

    private static void buildAuditPath(List<byte[]> leaves, int from, int to, int index, List<byte[]> path) {
        int n = to - from;
        if (n == 1) {
            return;
        }
        int k = largestPowerOfTwoLessThan(n);
        if (index < from + k) {
            buildAuditPath(leaves, from, from + k, index, path);
            path.add(rootHash(leaves, from + k, to));
        } else {
            buildAuditPath(leaves, from + k, to, index, path);
            path.add(rootHash(leaves, from, from + k));
        }
    }

    /**
     * 生成树大小 first 到 second（0 &lt; first &lt;= second，second 等于 leafHashes.size()）的一致性证明。
     */
    public static List<byte[]> consistencyPath(List<byte[]> leafHashes, int first, int second) {
        if (first < 1 || first > second || second != leafHashes.size()) {
            throw new IllegalArgumentException("invalid tree size range");
        }
        List<byte[]> path = new ArrayList<>();
        if (first == second) {
            return path;
        }
        buildConsistencyPath(leafHashes, 0, second, first, true, path);
        return path;
    }

    private static void buildConsistencyPath(List<byte[]> leaves, int from, int to, int m, boolean completeSubtree,
            List<byte[]> path) {
        int n = to - from;
        if (m == n) {
            if (!completeSubtree) {
                path.add(rootHash(leaves, from, to));
            }
            return;
        }
        int k = largestPowerOfTwoLessThan(n);
        if (m <= k) {
            buildConsistencyPath(leaves, from, from + k, m, completeSubtree, path);
            path.add(rootHash(leaves, from + k, to));
        } else {
            buildConsistencyPath(leaves, from + k, to, m - k, false, path);
            path.add(rootHash(leaves, from, from + k));
        }
    }

    /**
     * 本地验证包含证明，返回由叶哈希与审计路径重放出的根哈希（调用方与期望根比较）。
     */
    public static byte[] verifyInclusion(byte[] leafHash, long leafIndex, long treeSize, List<byte[]> auditPath) {
        if (treeSize < 1 || leafIndex < 0 || leafIndex >= treeSize) {
            throw new IllegalArgumentException("leaf index out of range");
        }
        long fn = leafIndex;
        long sn = treeSize - 1;
        byte[] r = leafHash;
        for (byte[] p : auditPath) {
            if ((fn & 1L) == 1L || fn == sn) {
                r = internalHash(p, r);
                while ((fn & 1L) == 0L && fn != 0) {
                    fn >>= 1;
                    sn >>= 1;
                }
            } else {
                r = internalHash(r, p);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return r;
    }

    /**
     * 本地验证一致性证明：由 firstRoot、secondRoot 与路径重放，返回是否同时匹配两个根。
     */
    public static boolean verifyConsistency(long first, long second, byte[] firstRoot, byte[] secondRoot,
            List<byte[]> consistencyPath) {
        if (first < 1 || second < 1 || first > second) {
            return false;
        }
        if (first == second) {
            return consistencyPath.isEmpty() && Arrays.equals(firstRoot, secondRoot);
        }
        List<byte[]> path = new ArrayList<>(consistencyPath);
        if ((first & (first - 1)) == 0) {
            // first 为 2 的幂时，将 firstRoot 前置到路径。
            path.add(0, firstRoot);
        }
        if (path.isEmpty()) {
            return false;
        }
        long fn = first - 1;
        long sn = second - 1;
        while ((fn & 1L) == 1L) {
            fn >>= 1;
            sn >>= 1;
        }
        byte[] fr = path.get(0);
        byte[] sr = path.get(0);
        for (int i = 1; i < path.size(); i++) {
            byte[] c = path.get(i);
            if (sn == 0) {
                return false;
            }
            if ((fn & 1L) == 1L || fn == sn) {
                fr = internalHash(c, fr);
                sr = internalHash(c, sr);
                while ((fn & 1L) == 0L && fn != 0) {
                    fn >>= 1;
                    sn >>= 1;
                }
            } else {
                sr = internalHash(sr, c);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return sn == 0 && Arrays.equals(fr, firstRoot) && Arrays.equals(sr, secondRoot);
    }

    /**
     * 小于 n 的最大 2 的幂（n 必须大于 1）。
     */
    static int largestPowerOfTwoLessThan(int n) {
        if (n <= 1) {
            throw new IllegalArgumentException("n must be greater than 1");
        }
        return Integer.highestOneBit(n - 1);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
