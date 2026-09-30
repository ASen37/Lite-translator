package com.example.test.translator.service;

/**
 * 翻译引擎接口。新增引擎时实现此接口即可接入。
 */
public interface Translator {

    /**
     * 翻译文本。
     *
     * @param text 待翻译文本
     * @param from 源语言代码，如 auto / zh / en
     * @param to   目标语言代码，如 zh / en
     * @return 翻译结果文本
     * @throws Exception 翻译失败时抛出
     */
    String translate(String text, String from, String to) throws Exception;
}
