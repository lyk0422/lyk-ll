package com.example.starter.trial.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区组排列器纯单元测试：每个区组 2:2 平衡、跨区组边界不出现连续 4 例同组。
 */
class BlockAllocatorTest {

    @Test
    void allPatternsAreBalancedWithTwoOfEachGroup() {
        for (String pattern : BlockAllocator.BALANCED_PATTERNS) {
            assertEquals(4, pattern.length());
            long treatment = pattern.chars().filter(c -> c == 'T').count();
            assertEquals(2, treatment, "区组 " + pattern + " 必须恰有 2 例试验组");
            assertEquals(2, 4 - treatment, "区组 " + pattern + " 必须恰有 2 例对照组");
            assertFalse(pattern.contains("TTTT"));
            assertFalse(pattern.contains("CCCC"));
        }
        assertEquals(6, new HashSet<>(BlockAllocator.BALANCED_PATTERNS).size());
    }

    @Test
    void boundaryIsRejectedOnlyWhenFourIdenticalGroupsWouldSpanBlocks() {
        // 上一区组以 TT 结尾，新区组 TT 开头 => 跨边界 TTTT
        assertTrue(BlockAllocator.violatesBoundary("CCTT", "TTCC"));
        // 上一区组以 CC 结尾，新区组 CC 开头 => 跨边界 CCCC
        assertTrue(BlockAllocator.violatesBoundary("TTCC", "CCTT"));
        // 交错边界合法
        assertFalse(BlockAllocator.violatesBoundary("CCTT", "TCTC"));
        assertFalse(BlockAllocator.violatesBoundary("TCTC", "CCTT"));
        assertFalse(BlockAllocator.violatesBoundary(null, "TTCC"));
    }

    @Test
    void longBlockSequenceNeverHasFourConsecutiveSameGroupAndKeepsBalance() {
        for (long seed : new long[]{1L, 42L, 20260101L, -7L}) {
            StringBuilder sequence = new StringBuilder();
            String previous = null;
            Set<String> usedPatterns = new HashSet<>();
            for (long blockNo = 1; blockNo <= 50; blockNo++) {
                String pattern = BlockAllocator.choosePattern(seed, blockNo, previous);
                usedPatterns.add(pattern);
                long treatment = pattern.chars().filter(c -> c == 'T').count();
                assertEquals(2, treatment, "种子 " + seed + " 区组 " + blockNo + " 比例失衡");
                sequence.append(pattern);
                previous = pattern;
            }
            assertFalse(sequence.toString().contains("TTTT"), "种子 " + seed + " 出现连续 4 例试验组");
            assertFalse(sequence.toString().contains("CCCC"), "种子 " + seed + " 出现连续 4 例对照组");
            assertEquals(6, usedPatterns.size(), "确定性序列应覆盖全部 6 种排列");
        }
    }

    @Test
    void patternSelectionIsDeterministicForSeedAndBlock() {
        String first = BlockAllocator.choosePattern(99L, 3, null);
        String second = BlockAllocator.choosePattern(99L, 3, null);
        assertEquals(first, second);
        assertEquals(TrialConstants.GROUP_TREATMENT, BlockAllocator.groupAt("TTCC", 0));
        assertEquals(TrialConstants.GROUP_CONTROL, BlockAllocator.groupAt("TTCC", 2));
    }

    @Test
    void shanghaiSyntheticSeedFirstBlocksAreReproducible() {
        long seed = 20260101L;
        String block1 = BlockAllocator.choosePattern(seed, 1, null);
        String block2 = BlockAllocator.choosePattern(seed, 2, block1);
        String block3 = BlockAllocator.choosePattern(seed, 3, block2);
        // 合成种子 20260101 下前三个区组排列可复现；边界 CC|TT、CC|CT 均不构成连续 4 同组。
        assertEquals("TTCC|TTCC|CTTC", block1 + "|" + block2 + "|" + block3);
    }
}
