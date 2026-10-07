package com.example.starter.cosmetic.testsupport;

import com.example.starter.cosmetic.service.FilingService;
import com.example.starter.cosmetic.support.LabelDigestSupport;
import com.example.starter.cosmetic.web.dto.ChangeFilingRequest;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 包装标签印次台账合成数据工厂：全部标识为合成。
 */
public final class LabelPrintFixtures {

    private LabelPrintFixtures() {
    }

    /** 0~36 月档适用年龄标识文本（与 FilingFixtures 儿童材料一致）。 */
    public static final String BAND_0_36_LABEL = "适用于0-36个月婴幼儿";
    /** 37~144 月档适用年龄标识文本（与 FilingFixtures 儿童材料一致）。 */
    public static final String BAND_37_144_LABEL = "适用于37-144个月儿童";

    /** 提交成人普通备案并推进时钟至公示期满生效，返回备案编号。 */
    public static String makeAdultEffective(FilingService filingService, MutableClock clock,
                                            String requestId, String productCode) {
        String filingNo = filingService.submit(
                FilingFixtures.ordinarySubmit(requestId, productCode)).filing().filingNo();
        clock.setInstant(clock.instant().plus(11, ChronoUnit.DAYS));
        filingService.expireDuePublicNotices(null);
        return filingNo;
    }

    /** 提交跨档儿童备案并推进时钟至公示期满生效，返回备案编号。 */
    public static String makeChildCrossBandEffective(FilingService filingService, MutableClock clock,
                                                     String requestId, String productCode) {
        String filingNo = filingService.submit(
                FilingFixtures.childSubmitCrossBand(requestId, productCode)).filing().filingNo();
        clock.setInstant(clock.instant().plus(11, ChronoUnit.DAYS));
        filingService.expireDuePublicNotices(null);
        return filingNo;
    }

    /** 跨档儿童印次的各年龄档独立标识摘要（与备案受理冻结文本逐档一致）。 */
    public static Map<String, String> crossBandDigests() {
        return Map.of(
                "BAND_0_36", LabelDigestSupport.bandDigest("BAND_0_36", BAND_0_36_LABEL),
                "BAND_37_144", LabelDigestSupport.bandDigest("BAND_37_144", BAND_37_144_LABEL));
    }

    /** 成人备案标签文本摘要（覆盖备案包装文字；未变更为 null）。 */
    public static String adultDigest(String packagingText) {
        return LabelDigestSupport.adultDigest(packagingText);
    }

    /** 实质变更（新增功效宣称）并推进时钟至新版本生效，触发旧印次原子禁用。 */
    public static void substantiveChangeAndEffectuate(FilingService filingService, MutableClock clock,
                                                      String requestId, String productCode,
                                                      Integer expectedVersion) {
        clock.setInstant(clock.instant().plus(1, ChronoUnit.HOURS));
        filingService.change(productCode, new ChangeFilingRequest(requestId, null, null,
                List.of("MOISTURIZING", "REFRESHING"), null, null,
                expectedVersion, null, null, null, null));
        clock.setInstant(clock.instant().plus(11, ChronoUnit.DAYS));
        filingService.expireDuePublicNotices(null);
    }
}
