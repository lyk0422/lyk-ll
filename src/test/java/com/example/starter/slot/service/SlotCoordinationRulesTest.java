package com.example.starter.slot.service;

import com.example.starter.slot.exception.SlotValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时刻规范化、覆盖周规范化、指纹与上一航季推导的纯规则单测。
 */
class SlotCoordinationRulesTest {

    @Test
    void slotTimeNormalizationEnforcesFiveMinuteGridAndFormat() {
        assertThat(SlotCoordinationService.normalizeSlotTime("08:00")).isEqualTo("08:00");
        assertThat(SlotCoordinationService.normalizeSlotTime(" 23:55 ")).isEqualTo("23:55");

        assertThatThrownBy(() -> SlotCoordinationService.normalizeSlotTime("08:03"))
                .isInstanceOf(SlotValidationException.class);
        assertThatThrownBy(() -> SlotCoordinationService.normalizeSlotTime("8:00"))
                .isInstanceOf(SlotValidationException.class);
        assertThatThrownBy(() -> SlotCoordinationService.normalizeSlotTime("24:00"))
                .isInstanceOf(SlotValidationException.class);
        assertThatThrownBy(() -> SlotCoordinationService.normalizeSlotTime(""))
                .isInstanceOf(SlotValidationException.class);
    }

    @Test
    void weeksAreDeduplicatedSortedAndPositiveOnly() {
        assertThat(SlotCoordinationService.normalizeWeeks(List.of(3, 1, 1, 2)))
                .containsExactly(1, 2, 3);

        assertThatThrownBy(() -> SlotCoordinationService.normalizeWeeks(List.of(1, 0)))
                .isInstanceOf(SlotValidationException.class);
        assertThatThrownBy(() -> SlotCoordinationService.normalizeWeeks(List.of()))
                .isInstanceOf(SlotValidationException.class);
    }

    @Test
    void fingerprintCoversRequestIdFlightTimeAndAllWeeks() {
        String a = SlotCoordinationService.fingerprint("R1", "1001", "08:00",
                SlotCoordinationService.normalizeWeeks(List.of(1, 2)));
        String reorderedWeeks = SlotCoordinationService.fingerprint("R1", "1001", "08:00",
                SlotCoordinationService.normalizeWeeks(List.of(2, 1)));
        String otherTime = SlotCoordinationService.fingerprint("R1", "1001", "08:05",
                SlotCoordinationService.normalizeWeeks(List.of(1, 2)));
        String otherFlight = SlotCoordinationService.fingerprint("R1", "1002", "08:00",
                SlotCoordinationService.normalizeWeeks(List.of(1, 2)));
        String otherWeeks = SlotCoordinationService.fingerprint("R1", "1001", "08:00",
                SlotCoordinationService.normalizeWeeks(List.of(1, 3)));

        // 覆盖周仅为顺序差异时规范化表示一致
        assertThat(a).isEqualTo(reorderedWeeks);
        assertThat(otherTime).isNotEqualTo(a);
        assertThat(otherFlight).isNotEqualTo(a);
        assertThat(otherWeeks).isNotEqualTo(a);
    }

    @Test
    void previousSeasonRollsBackWithinSameSeasonCode() {
        assertThat(SlotCoordinationService.previousSeason("S26")).isEqualTo("S25");
        assertThat(SlotCoordinationService.previousSeason("W00")).isEqualTo("W99");
        assertThat(SlotCoordinationService.previousSeason("UNKNOWN")).isEqualTo("UNKNOWN-P");
    }
}
