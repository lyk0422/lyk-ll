package com.example.starter.rolling.service;

import com.example.starter.rolling.api.dto.RollWindowRequest;
import com.example.starter.rolling.api.dto.ScheduleItemRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排产单纯规则校验器（不访问数据库）。
 *
 * <p>规则汇总：</p>
 * <ul>
 *   <li>同一轧辊周期（两次换辊之间）内宽度按“宽→窄”渐变，相邻宽度恰跳一档合法，跳两档及以上或由窄变宽违例；</li>
 *   <li>同宽度连续轧制里程不得超过 10 公里，恰达 10 公里合法，超出须先排换辊；换辊重置周期累计；</li>
 *   <li>烫辊材只能在换辊后前 5 块，恰第 5 块为最后烫辊位；</li>
 *   <li>作业与换辊窗口均按半开区间 [start,end) 判定，端点相接不冲突，内部重叠冲突；</li>
 *   <li>宽度必须存在于合成档位表；换辊窗口必须落在作业之前或相邻作业间隙中。</li>
 * </ul>
 */
public final class ScheduleValidator {

    /** 同宽度连续轧制里程上限（公里），恰达合法。 */
    public static final BigDecimal MAX_SAME_WIDTH_KM = BigDecimal.TEN;

    /** 换辊后允许排入烫辊材的块位数，恰第 5 块为最后烫辊位。 */
    public static final int MAX_WARMUP_POSITION = 5;

    private ScheduleValidator() {
    }

    /**
     * 校验后的块：携带换辊周期号与周期内块序。
     */
    public record ValidatedItem(ScheduleItemRequest item, int seq, int campaignNo, int seqInCampaign) {
    }

    /**
     * 校验结果：valid 为 true 时 items 携带周期编号可直接持久化；否则 violations 含全部违例明细。
     */
    public record Result(boolean valid, List<ValidatedItem> items, List<String> violations) {
    }

    /**
     * 执行整单校验，收集全部违例，不做短路。
     *
     * @param items   作业列表，顺序即轧制块序（从 1 开始）
     * @param windows 换辊窗口列表
     * @param grades  板宽毫米 -> 档位号（档位号越大板越窄）
     */
    public static Result validate(List<ScheduleItemRequest> items,
                                  List<RollWindowRequest> windows,
                                  Map<Integer, Integer> grades) {
        List<String> violations = new ArrayList<>();

        checkDuplicateContracts(items, violations);
        checkItemIntervals(items, violations);
        checkWindowIntervals(windows, violations);
        checkWidthsExist(items, grades, violations);
        checkItemOverlaps(items, violations);
        checkWindowOverlaps(windows, violations);
        checkWindowItemOverlaps(items, windows, violations);
        checkWindowPlacement(items, windows, violations);

        List<ValidatedItem> validated = assignCampaigns(items, windows);
        checkCampaignRules(validated, grades, violations);

        return new Result(violations.isEmpty(), List.copyOf(validated), List.copyOf(violations));
    }

    private static void checkDuplicateContracts(List<ScheduleItemRequest> items, List<String> violations) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String contract = items.get(i).contractNo();
            if (!seen.add(contract)) {
                violations.add("第" + (i + 1) + "块合同号 " + contract + " 在排产单内重复");
            }
        }
    }

    private static void checkItemIntervals(List<ScheduleItemRequest> items, List<String> violations) {
        for (int i = 0; i < items.size(); i++) {
            ScheduleItemRequest item = items.get(i);
            if (!item.startTime().isBefore(item.endTime())) {
                violations.add("第" + (i + 1) + "块（合同 " + item.contractNo()
                        + "）作业区间非法，开始时刻必须早于结束时刻");
            }
        }
    }

    private static void checkWindowIntervals(List<RollWindowRequest> windows, List<String> violations) {
        for (int i = 0; i < windows.size(); i++) {
            RollWindowRequest window = windows.get(i);
            if (!window.startTime().isBefore(window.endTime())) {
                violations.add("第" + (i + 1) + "个换辊窗口区间非法，开始时刻必须早于结束时刻");
            }
        }
    }

    private static void checkWidthsExist(List<ScheduleItemRequest> items,
                                         Map<Integer, Integer> grades,
                                         List<String> violations) {
        for (int i = 0; i < items.size(); i++) {
            ScheduleItemRequest item = items.get(i);
            if (!grades.containsKey(item.widthMm())) {
                violations.add("第" + (i + 1) + "块（合同 " + item.contractNo()
                        + "）板宽 " + item.widthMm() + " 毫米不在合成宽度档位表中");
            }
        }
    }

    private static void checkItemOverlaps(List<ScheduleItemRequest> items, List<String> violations) {
        // 任意两块作业时间内部重叠即冲突（半开区间 [start,end)：a.start < b.end 且 b.start < a.end）；
        // 端点相接合法。按合同对去重报告，避免同一对报两次。
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                ScheduleItemRequest a = items.get(i);
                ScheduleItemRequest b = items.get(j);
                if (a.startTime().isBefore(b.endTime()) && b.startTime().isBefore(a.endTime())) {
                    violations.add("第" + (i + 1) + "块（合同 " + a.contractNo() + "）与第" + (j + 1)
                            + "块（合同 " + b.contractNo() + "）作业时间内部重叠，半开区间端点相接才合法");
                }
            }
        }
    }

    private static void checkWindowOverlaps(List<RollWindowRequest> windows, List<String> violations) {
        List<RollWindowRequest> sorted = windows.stream().sorted(java.util.Comparator
                .comparing(RollWindowRequest::startTime)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).endTime().isAfter(sorted.get(i).startTime())) {
                violations.add("换辊窗口之间存在内部重叠，半开区间端点相接才合法");
            }
        }
    }

    private static void checkWindowItemOverlaps(List<ScheduleItemRequest> items,
                                                List<RollWindowRequest> windows,
                                                List<String> violations) {
        for (int w = 0; w < windows.size(); w++) {
            RollWindowRequest window = windows.get(w);
            for (int i = 0; i < items.size(); i++) {
                ScheduleItemRequest item = items.get(i);
                // 内部重叠：window.start < item.end 且 item.start < window.end
                if (window.startTime().isBefore(item.endTime())
                        && item.startTime().isBefore(window.endTime())) {
                    violations.add("第" + (w + 1) + "个换辊窗口与第" + (i + 1) + "块（合同 "
                            + item.contractNo() + "）作业时间内部重叠");
                }
            }
        }
    }

    private static void checkWindowPlacement(List<ScheduleItemRequest> items,
                                             List<RollWindowRequest> windows,
                                             List<String> violations) {
        if (items.isEmpty()) {
            return;
        }
        for (int w = 0; w < windows.size(); w++) {
            if (!fitsTimeline(items, windows.get(w))) {
                violations.add("第" + (w + 1)
                        + "个换辊窗口未落在首块之前或相邻作业的间隙中（半开区间端点相接合法），其后也无轧制作业");
            }
        }
    }

    /**
     * 窗口落在时间轴合法位置：首块之前（window.end <= first.start），
     * 或第 i 块与第 i+1 块之间（item[i].end <= window.start 且 window.end <= item[i+1].start）。
     */
    private static boolean fitsTimeline(List<ScheduleItemRequest> items, RollWindowRequest window) {
        if (!window.endTime().isAfter(items.get(0).startTime())) {
            return true;
        }
        for (int i = 0; i + 1 < items.size(); i++) {
            if (!items.get(i).endTime().isAfter(window.startTime())
                    && !window.endTime().isAfter(items.get(i + 1).startTime())) {
                return true;
            }
        }
        return false;
    }

    private static List<ValidatedItem> assignCampaigns(List<ScheduleItemRequest> items,
                                                       List<RollWindowRequest> windows) {
        Map<Integer, Integer> seqInCampaign = new HashMap<>();
        List<ValidatedItem> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ScheduleItemRequest item = items.get(i);
            int campaignNo = 1;
            for (RollWindowRequest window : windows) {
                if (!window.endTime().isAfter(item.startTime())) {
                    campaignNo++;
                }
            }
            int seq = seqInCampaign.merge(campaignNo, 1, Integer::sum);
            result.add(new ValidatedItem(item, i + 1, campaignNo, seq));
        }
        return result;
    }

    private static void checkCampaignRules(List<ValidatedItem> items,
                                           Map<Integer, Integer> grades,
                                           List<String> violations) {
        // 按周期分组但保持块序，逐周期检查宽度渐变、同宽里程与烫辊位。
        Map<Integer, Integer> previousGrade = new HashMap<>();
        Map<Integer, Integer> previousWidth = new HashMap<>();
        Map<Integer, BigDecimal> sameWidthKm = new HashMap<>();

        for (ValidatedItem vi : items) {
            ScheduleItemRequest item = vi.item();
            int campaign = vi.campaignNo();
            Integer grade = grades.get(item.widthMm());
            if (grade == null) {
                continue; // 不存在的档位已在 checkWidthsExist 中报告
            }

            if (vi.seqInCampaign() > MAX_WARMUP_POSITION && item.warmup()) {
                violations.add("第" + vi.seq() + "块（合同 " + item.contractNo()
                        + "）为烫辊材，但位于换辊周期 " + campaign + " 的第 " + vi.seqInCampaign()
                        + " 块，烫辊材须在换辊后前 " + MAX_WARMUP_POSITION + " 块排入");
            }

            Integer prevGrade = previousGrade.get(campaign);
            if (prevGrade != null) {
                if (grade < prevGrade) {
                    violations.add("第" + vi.seq() + "块（合同 " + item.contractNo() + "）板宽 "
                            + item.widthMm() + " 毫米较前块变宽，轧辊周期内宽度须按宽→窄渐变");
                } else if (grade - prevGrade > 1) {
                    violations.add("第" + vi.seq() + "块（合同 " + item.contractNo() + "）板宽由档位 "
                            + prevGrade + " 跳到档位 " + grade + "，相邻宽度跳档不得超过一级");
                }
            }

            BigDecimal running;
            if (Integer.valueOf(item.widthMm()).equals(previousWidth.get(campaign))) {
                running = sameWidthKm.get(campaign).add(item.rollingKm());
            } else {
                running = item.rollingKm();
            }
            sameWidthKm.put(campaign, running);
            if (running.compareTo(MAX_SAME_WIDTH_KM) > 0) {
                violations.add("第" + vi.seq() + "块（合同 " + item.contractNo() + "）使板宽 "
                        + item.widthMm() + " 毫米连续轧制里程达 " + running.stripTrailingZeros().toPlainString()
                        + " 公里，超过轧辊周期上限 " + MAX_SAME_WIDTH_KM + " 公里，须先排换辊");
            }

            previousGrade.put(campaign, grade);
            previousWidth.put(campaign, item.widthMm());
        }
    }
}
