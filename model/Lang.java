package com.example.test.translator.model;

/**
 * 语言枚举：翻译应用支持的语言及其对应的百度翻译接口代码。
 */
public enum Lang {

    AUTO("auto", "自动检测"),
    ZH("zh", "中文"),
    EN("en", "英文");

    /** 百度翻译接口使用的语言代码 */
    private final String code;

    /** 界面显示的文本 */
    private final String label;

    Lang(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
