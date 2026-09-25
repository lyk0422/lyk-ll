package com.example.starter.trial.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 区组长度 4、两组 1:1 的平衡区组排列器。
 *
 * <p>全部 6 种排列（T=试验组，C=对照组）：TTCC、TCTC、TCCT、CTTC、CTCT、CCTT，
 * 每个区组天然保持 2:2 设计比例，因此区内不可能出现连续 4 例同组。
 *
 * <p>区组排列由中心合成种子与区组号确定性推导（不依赖外部随机系统），
 * 选择新区组时排除会与上一区组尾部拼接成连续 4 例同组的排列：
 * 上一区组以 TT 结尾则新区组不得以 TT 开头（排除 TTCC）；
 * 上一区组以 CC 结尾则新区组不得以 CC 开头（排除 CCTT）。
 */
public final class BlockAllocator {

    /** 六个长度 4、各含 2T2C 的平衡排列。 */
    static final List<String> BALANCED_PATTERNS = List.of(
            "TTCC", "TCTC", "TCCT", "CTTC", "CTCT", "CCTT");

    private BlockAllocator() {
    }

    /**
     * 选择新区组排列。
     *
     * @param seed            中心合成随机种子
     * @param blockNo         待开启的区组号（从 1 开始）
     * @param previousPattern 上一区组排列；首区组传 null
     * @return 长度 4 的 T/C 排列
     */
    public static String choosePattern(long seed, long blockNo, String previousPattern) {
        List<String> allowed = new ArrayList<>();
        for (String pattern : BALANCED_PATTERNS) {
            if (violatesBoundary(previousPattern, pattern)) {
                continue;
            }
            allowed.add(pattern);
        }
        int index = Math.floorMod(deterministicInt(seed, blockNo), allowed.size());
        return allowed.get(index);
    }

    /**
     * 仅当上一区组尾部与新区组头部都是同一组的连续两例时，拼接会出现连续 4 例同组。
     */
    static boolean violatesBoundary(String previousPattern, String newPattern) {
        if (previousPattern == null || previousPattern.length() < 4) {
            return false;
        }
        char prev3 = previousPattern.charAt(2);
        char prev4 = previousPattern.charAt(3);
        char new1 = newPattern.charAt(0);
        char new2 = newPattern.charAt(1);
        return prev3 == prev4 && new1 == new2 && prev4 == new1;
    }

    /** 取排列中某位置（0-3）的真实组别。 */
    public static String groupAt(String pattern, int position) {
        return pattern.charAt(position) == 'T'
                ? TrialConstants.GROUP_TREATMENT
                : TrialConstants.GROUP_CONTROL;
    }

    private static int deterministicInt(long seed, long blockNo) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((seed + ":" + blockNo).getBytes(StandardCharsets.UTF_8));
            int value = 0;
            for (int i = 0; i < 4; i++) {
                value = (value << 8) | (hash[i] & 0xFF);
            }
            return value;
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
