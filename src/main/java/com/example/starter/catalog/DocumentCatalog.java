package com.example.starter.catalog;

import java.util.List;

/**
 * 合成申报资料目录。资料审查按该目录一次性核对，缺项一次性告知补正。
 */
public final class DocumentCatalog {

    /** 必备资料代码（合成目录，共 7 项）。 */
    public static final String REGISTER_CERT = "REGISTER_CERT";
    public static final String PRODUCTION_SUMMARY = "PRODUCTION_SUMMARY";
    public static final String BATCH_RECORD = "BATCH_RECORD";
    public static final String QUALITY_STANDARD = "QUALITY_STANDARD";
    public static final String INSPECTION_REPORT = "INSPECTION_REPORT";
    public static final String STABILITY_REPORT = "STABILITY_REPORT";
    public static final String LABEL_INSERT = "LABEL_INSERT";

    private static final List<String> REQUIRED = List.of(
            REGISTER_CERT,
            PRODUCTION_SUMMARY,
            BATCH_RECORD,
            QUALITY_STANDARD,
            INSPECTION_REPORT,
            STABILITY_REPORT,
            LABEL_INSERT
    );

    private DocumentCatalog() {
    }

    /** 返回合成目录要求的全部必备资料代码（有序）。 */
    public static List<String> requiredDocuments() {
        return REQUIRED;
    }
}
