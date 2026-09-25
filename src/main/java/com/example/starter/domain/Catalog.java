package com.example.starter.domain;

import java.util.List;

/**
 * 合成资料目录与关键检验项目定义（全部为合成数据，不对应真实法规条目）。
 */
public final class Catalog {

    private Catalog() {
    }

    /**
     * 资料目录项。
     *
     * @param code     目录项编码
     * @param name     目录项名称
     * @param required 是否必交
     */
    public record CatalogItem(String code, String name, boolean required) {
    }

    /** 合成资料目录：申报时按此目录核对，缺项一次性告知 */
    public static final List<CatalogItem> DOCUMENT_CATALOG = List.of(
            new CatalogItem("LICENSE", "生产许可证（合成）", true),
            new CatalogItem("QUALITY_STD", "质量标准（合成）", true),
            new CatalogItem("SELF_TEST_REPORT", "企业自检报告（合成）", true),
            new CatalogItem("PROCESS_RECORD", "批生产记录（合成）", true),
            new CatalogItem("SUMMARY", "批总结（合成）", true),
            new CatalogItem("STABILITY", "稳定性考察资料（合成）", false)
    );

    /** 关键检验项目编码：效力、无菌、异常毒性，须全项合格 */
    public static final List<CatalogItem> CRITICAL_INSPECTION_ITEMS = List.of(
            new CatalogItem("POTENCY", "效力（合成）", true),
            new CatalogItem("STERILITY", "无菌（合成）", true),
            new CatalogItem("ABNORMAL_TOXICITY", "异常毒性（合成）", true)
    );

    /** 补正时限：5个工作日（不含申报当日，遇周末顺延） */
    public static final int CORRECTION_WORKING_DAYS = 5;

    /** 公示期：7个自然日（UTC日界），恰第7日期满生效 */
    public static final int PUBLICITY_DAYS = 7;

    /** 同企业同品种连续不合格批次数阈值，恰连续3批触发暂停受理 */
    public static final int CONSECUTIVE_FAIL_THRESHOLD = 3;
}
