package com.example.starter.trial.service;

import com.example.starter.trial.domain.Assignment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 按中心分层、固定区组长度 4 的区组随机化器。
 *
 * <p>每个区组内含 2 例试验组与 2 例对照组（设计比例 1:1），区组内顺序由
 * “试验+中心”种子、区组序号确定性派生，因此：</p>
 * <ul>
 *   <li>任意入组位置的分组都可独立、确定地重算，不依赖有状态的随机流；</li>
 *   <li>每个区组严格保持 2:2；</li>
 *   <li>选择区组排列时同时校验与上一区组尾部的衔接，任何窗口（含跨区组边界）
 *       都不允许出现连续 4 例同组；</li>
 *   <li>不同中心使用不同种子，分层互不影响。</li>
 * </ul>
 */
@Component
public class BlockRandomizer {

    /** 固定区组长度：4（2 试验 + 2 对照）。 */
    public static final int BLOCK_SIZE = 4;

    /**
     * 计算中心内第 {@code position} 例入组（1-based）的分组。
     *
     * @param seed     该“试验+中心”的随机种子
     * @param position 中心内入组次序（从 1 开始）
     * @return 试验组或对照组
     */
    public Assignment assign(long seed, long position) {
        if (position < 1) {
            throw new IllegalArgumentException("position must be >= 1");
        }
        long blockIndex = (position - 1) / BLOCK_SIZE;
        int offset = (int) ((position - 1) % BLOCK_SIZE);
        List<Assignment> previous = null;
        for (long i = 0; i < blockIndex; i++) {
            previous = blockPermutation(seed, i, previous);
        }
        return blockPermutation(seed, blockIndex, previous).get(offset);
    }

    /**
     * 生成指定区组的两组分配排列（2 试验 + 2 对照的洗牌结果）。
     * 候选种子由中心种子、区组序号与尝试次数派生；若候选排列与上一区组
     * 尾部衔接会产生连续 4 例同组，则确定性地尝试下一粒种子。
     * 整个过程对相同入参始终得到相同结果。
     */
    private List<Assignment> blockPermutation(long seed, long blockIndex, List<Assignment> previous) {
        Assignment tail = previous == null ? null : previous.get(BLOCK_SIZE - 1);
        int trailing = previous == null ? 0 : trailingSameCount(previous);
        for (long attempt = 0; attempt < 64; attempt++) {
            List<Assignment> block = shuffledBlock(seed, blockIndex, attempt);
            if (tail == null || trailing < 2 || block.get(0) != tail
                    || trailing + leadingSameCount(block) < 4) {
                return block;
            }
        }
        // 2T2C 排列只有 6 种，边界冲突的排列最多 2 种，理论上不可达；保留确定性兜底。
        return shuffledBlock(seed, blockIndex, 64);
    }

    private List<Assignment> shuffledBlock(long seed, long blockIndex, long attempt) {
        List<Assignment> block = new ArrayList<>(BLOCK_SIZE);
        block.add(Assignment.TREATMENT);
        block.add(Assignment.TREATMENT);
        block.add(Assignment.CONTROL);
        block.add(Assignment.CONTROL);
        long derivedSeed = seed ^ (blockIndex * 0x9E3779B97F4A7C15L
                + attempt * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L);
        Collections.shuffle(block, new Random(derivedSeed));
        return block;
    }

    private int trailingSameCount(List<Assignment> block) {
        int count = 1;
        Assignment last = block.get(BLOCK_SIZE - 1);
        for (int i = BLOCK_SIZE - 2; i >= 0 && block.get(i) == last; i--) {
            count++;
        }
        return count;
    }

    private int leadingSameCount(List<Assignment> block) {
        int count = 1;
        Assignment first = block.get(0);
        for (int i = 1; i < BLOCK_SIZE && block.get(i) == first; i++) {
            count++;
        }
        return count;
    }
}
