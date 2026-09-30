package com.example.test.translator.service;

/**
 * 翻译异常：携带对用户友好的错误信息。
 */
public class TranslationException extends Exception {

    public TranslationException(String message) {
        super(message);
    }
}
