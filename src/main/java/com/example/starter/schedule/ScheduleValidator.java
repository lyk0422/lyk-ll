package com.example.starter.schedule;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.example.starter.schedule.ScheduleRequests.ItemSpec;
import com.example.starter.schedule.ScheduleRequests.RollWindowSpec;
import com.example.starter.schedule.ScheduleResponses.Violation;

/**
 * 排产规则校验器：输出全部违例明细，由调用方整单拒绝。
 *
 * <p>规则：
 * 1. 同一轧制单元内宽度按 宽→窄 渐变排列；
 * 2. 相邻宽度跳档不得超过合成档位表一级，恰跳一级合法；
 * 3. 同宽度连续轧制里程不得超过轧辊周期上限 10 公里，恰达 10 公里合法，超出须先排换辊；
 * 4. 烫辊材须在换辊后前 5 块排入，恰第 5 块为最后烫辊位；
 * 5. 换辊窗口与轧制作业按半开区间 [start, end) 判定：端点相接不冲突，内部重叠冲突。
 */
@Component
public class ScheduleValidator {

    /** 轧辊周期上限（公里）。 */
    public static final BigDecimal ROLL_CYCLE_LIMIT_KM = new BigDecimal("10");

    /** 换辊后烫辊材允许的最大块数。 */
    public static final int WARMUP_SLOTS = 5;

    /**
     * 校验整单，返回全部违例明细（空列表表示通过）。
     */
    public List<Violation> validate(List<ItemSpec> items, List<RollWindowSpec> windows) {
        List<Violation> violations = new ArrayList<>();
        List<ItemSpec> ordered = items.stream()
                .sorted(Comparator.comparing(ItemSpec::seq))
                .toList();
        List<RollWindowSpec> sortedWindows = windows == null ? List.of()
                : windows.stream()
                        .sorted(Comparator.comparing(RollWindowSpec::start))
                        .toList();

        checkTimeRanges(ordered, sortedWindows, violations);
        checkWidthOrderAndJump(ordered, violations);
        checkRollCycleRules(ordered, sortedWindows, violations);
        checkWindowConflicts(ordered, sortedWindows, violations);
        return violations;
    }

    /** 基础时间合法性：计划与窗口均为 start < end。 */
    private void checkTimeRanges(List<ItemSpec> items, List<RollWindowSpec> windows,
                                 List<Violation> violations) {
        for (ItemSpec item : items) {
            if (!item.plannedStart().isBefore(item.plannedEnd())) {
                violations.add(new Violation("TIME_RANGE",
                        "合同 " + item.contractNo() + " 计划开始须早于结束", item.seq()));
            }
        }
        for (RollWindowSpec w : windows) {
            if (!w.start().isBefore(w.end())) {
                violations.add(new Violation("TIME_RANGE",
                        "换辊窗口 " + w.start() + " 开始须早于结束", null));
            }
        }
    }

    /** 规则 1/2：宽度 宽→窄 渐变，且相邻跳档不超过一级。 */
    private void checkWidthOrderAndJump(List<ItemSpec> items, List<Violation> violations) {
        for (int i = 0; i < items.size(); i++) {
            Integer currentIdx = WidthGradeTable.indexOf(items.get(i).widthMm());
            if (currentIdx == null) {
                violations.add(new Violation("WIDTH_UNKNOWN",
                        "宽度 " + items.get(i).widthMm() + "mm 不在合成档位表 " + WidthGradeTable.grades(),
                        items.get(i).seq()));
                continue;
            }
            if (i == 0) {
                continue;
            }
            Integer prevIdx = WidthGradeTable.indexOf(items.get(i - 1).widthMm());
            if (prevIdx == null) {
                continue;
            }
            if (currentIdx < prevIdx) {
                violations.add(new Violation("WIDTH_ORDER",
                        "宽度须按宽→窄渐变：" + items.get(i - 1).widthMm() + "mm 后不得回宽到 "
                                + items.get(i).widthMm() + "mm",
                        items.get(i).seq()));
            } else if (currentIdx - prevIdx > 1) {
                violations.add(new Violation("WIDTH_JUMP",
                        "相邻宽度跳档超过一级：" + items.get(i - 1).widthMm() + "mm → "
                                + items.get(i).widthMm() + "mm",
                        items.get(i).seq()));
            }
        }
    }

    /** 规则 3/4：按换辊周期检查同宽度连续里程上限与烫辊材位置。 */
    private void checkRollCycleRules(List<ItemSpec> items, List<RollWindowSpec> windows,
                                     List<Violation> violations) {
        int positionInCycle = 0;
        Integer runWidth = null;
        BigDecimal runMileage = BigDecimal.ZERO;
        int runStartSeq = 0;
        LocalDateTime prevEnd = null;

        for (ItemSpec item : items) {
            if (prevEnd != null && windowBetween(windows, prevEnd, item.plannedStart())) {
                // 换辊窗口落在相邻作业间隙：进入新轧辊周期
                positionInCycle = 0;
                runWidth = null;
                runMileage = BigDecimal.ZERO;
            }
            positionInCycle++;

            if (runWidth != null && runWidth.intValue() == item.widthMm()) {
                runMileage = runMileage.add(item.mileageKm());
            } else {
                runWidth = item.widthMm();
                runMileage = item.mileageKm();
                runStartSeq = item.seq();
            }
            if (runMileage.compareTo(ROLL_CYCLE_LIMIT_KM) > 0) {
                violations.add(new Violation("MILEAGE_LIMIT",
                        "宽度 " + runWidth + "mm 连续轧制里程 " + runMileage.stripTrailingZeros()
                                + "km 超过轧辊周期上限 10km（自第 " + runStartSeq + " 块起），须先排换辊",
                        item.seq()));
            }

            if (item.warmup() && positionInCycle > WARMUP_SLOTS) {
                violations.add(new Violation("WARMUP_POSITION",
                        "烫辊材合同 " + item.contractNo() + " 位于换辊后第 " + positionInCycle
                                + " 块，超出前 " + WARMUP_SLOTS + " 块烫辊位",
                        item.seq()));
            }
            prevEnd = item.plannedEnd();
        }
    }

    /** 判断 [prevEnd, nextStart) 间隙内是否落有换辊窗口。 */
    private boolean windowBetween(List<RollWindowSpec> windows, LocalDateTime prevEnd,
                                  LocalDateTime nextStart) {
        for (RollWindowSpec w : windows) {
            if (!w.start().isBefore(prevEnd) && w.start().isBefore(nextStart)) {
                return true;
            }
        }
        return false;
    }

    /** 规则 5：换辊窗口与轧制作业半开区间 [start, end) 内部重叠判定。 */
    private void checkWindowConflicts(List<ItemSpec> items, List<RollWindowSpec> windows,
                                      List<Violation> violations) {
        for (RollWindowSpec w : windows) {
            for (ItemSpec item : items) {
                boolean overlap = w.start().isBefore(item.plannedEnd())
                        && item.plannedStart().isBefore(w.end());
                if (overlap) {
                    violations.add(new Violation("WINDOW_CONFLICT",
                            "换辊窗口 [" + w.start() + ", " + w.end() + ") 与合同 " + item.contractNo()
                                    + " 作业 [" + item.plannedStart() + ", " + item.plannedEnd() + ") 内部重叠",
                            item.seq()));
                }
            }
        }
    }
}
