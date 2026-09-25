package com.example.starter.trial.service;

import com.example.starter.trial.domain.Assignment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区组随机化器单元测试：区组 2:2 设计比例、跨区组不允许连续 4 例同组、
 * 确定性可重算、中心种子分层差异。
 */
class BlockRandomizerTest {

    private final BlockRandomizer randomizer = new BlockRandomizer();

    @Test
    void everyBlockOfFourKeepsTwoTreatmentAndTwoControl() {
        long[] seeds = {0L, 1L, -1L, 42L, 987654321L, Long.MIN_VALUE, Long.MAX_VALUE};
        for (long seed : seeds) {
            for (int start = 1; start <= 400; start += BlockRandomizer.BLOCK_SIZE) {
                long treatment = 0;
                long control = 0;
                for (int offset = 0; offset < BlockRandomizer.BLOCK_SIZE; offset++) {
                    Assignment a = randomizer.assign(seed, start + offset);
                    if (a == Assignment.TREATMENT) {
                        treatment++;
                    } else {
                        control++;
                    }
                }
                assertEquals(2, treatment, "seed=" + seed + " block starting " + start);
                assertEquals(2, control, "seed=" + seed + " block starting " + start);
            }
        }
    }

    @Test
    void neverProducesFourConsecutiveSameGroupIncludingAcrossBlocks() {
        long[] seeds = {0L, 7L, -99L, 123456789L};
        for (long seed : seeds) {
            List<Assignment> sequence = new ArrayList<>();
            for (int position = 1; position <= 4000; position++) {
                sequence.add(randomizer.assign(seed, position));
            }
            for (int i = 0; i + 3 < sequence.size(); i++) {
                Set<Assignment> window = new HashSet<>(sequence.subList(i, i + 4));
                assertEquals(2, window.size(),
                        "four consecutive identical assignments at index " + i + " seed " + seed);
            }
        }
    }

    @Test
    void assignmentIsDeterministicForSameSeedAndPosition() {
        for (int position = 1; position <= 200; position++) {
            assertEquals(randomizer.assign(777L, position), randomizer.assign(777L, position));
        }
    }

    @Test
    void differentSiteSeedsStratifyIndependently() {
        boolean anyDifference = false;
        for (int position = 1; position <= 500; position++) {
            if (randomizer.assign(111L, position) != randomizer.assign(222L, position)) {
                anyDifference = true;
                break;
            }
        }
        assertTrue(anyDifference, "不同中心种子在足够长序列上应产生不同分配");
    }

    @Test
    void firstPositionIsAValidAssignmentAndPositionZeroRejected() {
        Assignment first = randomizer.assign(1L, 1);
        assertTrue(first == Assignment.TREATMENT || first == Assignment.CONTROL);
        assertThrows(IllegalArgumentException.class, () -> randomizer.assign(1L, 0));
    }

    @Test
    void sequenceContainsBothGroupsAndIsNotConstant() {
        List<Assignment> sequence = new ArrayList<>();
        for (int position = 1; position <= 40; position++) {
            sequence.add(randomizer.assign(555L, position));
        }
        assertTrue(sequence.contains(Assignment.TREATMENT));
        assertTrue(sequence.contains(Assignment.CONTROL));
        assertFalse(sequence.stream().allMatch(a -> a == Assignment.TREATMENT));
        assertFalse(sequence.stream().allMatch(a -> a == Assignment.CONTROL));
    }
}
