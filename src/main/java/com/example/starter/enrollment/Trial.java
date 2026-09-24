package com.example.starter.enrollment;

/**
 * 试验主数据；treatCode/controlCode 为两组对应的盲态编码。
 */
public record Trial(long id, String code, char treatCode, char controlCode) {

    /**
     * 实际组别（T/C）映射为盲态编码。
     */
    public char blindedCodeOf(char actualArm) {
        return actualArm == 'T' ? treatCode : controlCode;
    }
}
