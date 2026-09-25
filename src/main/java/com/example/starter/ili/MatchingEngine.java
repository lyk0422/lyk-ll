package com.example.starter.ili;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 缺陷对齐引擎：按绝对里程容差 ±0.5m（含边界）贪心匹配旧新两回合缺陷，
 * 一对多候选时取里程差最小者，每个缺陷最多出现在一个匹配对中。
 */
public final class MatchingEngine {

    /** 匹配容差（米），里程差绝对值小于等于该值即视为候选匹配。 */
    public static final BigDecimal TOLERANCE_M = new BigDecimal("0.5");

    private MatchingEngine() {
    }

    /** 参与匹配的缺陷读数。 */
    public record DefectInput(String defectRef, BigDecimal absMileageM, BigDecimal depthPct) {
    }

    /** 一个匹配对及其里程差绝对值（米）。 */
    public record MatchedPair(DefectInput oldDefect, DefectInput newDefect, BigDecimal mileageDiffM) {
    }

    /** 对齐结果：匹配对、新发现（仅新回合存在）、已消失（仅旧回合存在）。 */
    public record MatchResult(List<MatchedPair> pairs, List<DefectInput> newFindings,
            List<DefectInput> disappeared) {
    }

    /**
     * 对两个回合的缺陷读数做里程对齐。
     * 里程差绝对值不超过 0.5m（含恰好 0.5m）构成候选对；候选按里程差升序贪心取用，
     * 一对多时取里程差最小者，且每个缺陷最多进入一个匹配对。
     *
     * @param oldDefects 旧回合缺陷
     * @param newDefects 新回合缺陷
     * @return 对齐结果，未匹配的新回合缺陷记为新发现，未匹配的旧回合缺陷记为已消失
     */
    public static MatchResult match(List<DefectInput> oldDefects, List<DefectInput> newDefects) {
        record Candidate(int oldIdx, int newIdx, BigDecimal diff) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < oldDefects.size(); i++) {
            for (int j = 0; j < newDefects.size(); j++) {
                BigDecimal diff = oldDefects.get(i).absMileageM()
                        .subtract(newDefects.get(j).absMileageM()).abs();
                if (diff.compareTo(TOLERANCE_M) <= 0) {
                    candidates.add(new Candidate(i, j, diff));
                }
            }
        }
        // 里程差升序，差值相同时按缺陷编号字典序保证结果确定
        candidates.sort(Comparator
                .comparing(Candidate::diff)
                .thenComparing(c -> oldDefects.get(c.oldIdx()).defectRef())
                .thenComparing(c -> newDefects.get(c.newIdx()).defectRef()));

        boolean[] oldUsed = new boolean[oldDefects.size()];
        boolean[] newUsed = new boolean[newDefects.size()];
        List<MatchedPair> pairs = new ArrayList<>();
        for (Candidate c : candidates) {
            if (oldUsed[c.oldIdx()] || newUsed[c.newIdx()]) {
                continue;
            }
            oldUsed[c.oldIdx()] = true;
            newUsed[c.newIdx()] = true;
            pairs.add(new MatchedPair(oldDefects.get(c.oldIdx()), newDefects.get(c.newIdx()), c.diff()));
        }
        pairs.sort(Comparator.comparing(p -> p.oldDefect().defectRef()));

        List<DefectInput> disappeared = new ArrayList<>();
        for (int i = 0; i < oldDefects.size(); i++) {
            if (!oldUsed[i]) {
                disappeared.add(oldDefects.get(i));
            }
        }
        List<DefectInput> newFindings = new ArrayList<>();
        for (int j = 0; j < newDefects.size(); j++) {
            if (!newUsed[j]) {
                newFindings.add(newDefects.get(j));
            }
        }
        return new MatchResult(List.copyOf(pairs), List.copyOf(newFindings), List.copyOf(disappeared));
    }
}
