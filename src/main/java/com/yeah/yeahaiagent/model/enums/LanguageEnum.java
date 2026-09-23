package com.yeah.yeahaiagent.model.enums;

import java.util.Locale;

/**
 * 行程与周边服务的输出语言。未传或无法识别时默认中文。
 */
public enum LanguageEnum {

    EN("en"),
    ZH("zh");

    private final String code;

    LanguageEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public boolean isZh() {
        return this == ZH;
    }

    /**
     * 按请求参数解析；空值或无法识别时回落为中文。
     */
    public static LanguageEnum fromCode(String lang) {
        if (lang == null || lang.isBlank()) {
            return ZH;
        }
        String normalized = lang.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("en")) {
            return EN;
        }
        return ZH;
    }

    public String text(String zh, String en) {
        return isZh() ? zh : en;
    }
}
