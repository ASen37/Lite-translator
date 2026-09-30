package com.example.test.translator.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 百度翻译引擎实现：调用通用文本翻译 API，基于 MD5 签名认证。
 */
public class BaiduTranslator implements Translator {

    private static final String API_URL = "https://fanyi-api.baidu.com/api/trans/vip/translate";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 匹配 JSON 中的 "dst":"..." 译文字段 */
    private static final Pattern DST_PATTERN =
            Pattern.compile("\"dst\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** 匹配错误码与错误消息，避免每次翻译重复编译正则 */
    private static final Pattern ERROR_CODE_PATTERN =
            Pattern.compile("\"error_code\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ERROR_MSG_PATTERN =
            Pattern.compile("\"error_msg\"\\s*:\\s*\"([^\"]*)\"");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String appId;
    private final String secretKey;
    private final HttpClient httpClient;

    public BaiduTranslator(String appId, String secretKey) {
        this.appId = appId;
        this.secretKey = secretKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public String translate(String text, String from, String to) throws Exception {
        long salt = ThreadLocalRandom.current().nextLong(100000000L, 10000000000L);
        String sign = md5Hex(appId + text + salt + secretKey);

        String body = "q=" + urlEncode(text)
                + "&from=" + urlEncode(from)
                + "&to=" + urlEncode(to)
                + "&appid=" + urlEncode(appId)
                + "&salt=" + salt
                + "&sign=" + sign;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return parseResponse(response.body());
    }

    /**
     * 解析百度响应：成功时返回译文（多段以换行拼接），失败时抛出带错误码的异常。
     */
    private String parseResponse(String json) throws Exception {
        String errorCode = extractField(ERROR_CODE_PATTERN, json);
        if (errorCode != null) {
            String errorMsg = extractField(ERROR_MSG_PATTERN, json);
            throw new TranslationException("百度翻译失败(" + errorCode + ")：" + errorMsg);
        }

        List<String> parts = new ArrayList<>();
        Matcher matcher = DST_PATTERN.matcher(json);
        while (matcher.find()) {
            parts.add(decodeJsonString(matcher.group(1)));
        }
        if (parts.isEmpty()) {
            throw new TranslationException("翻译结果为空");
        }
        return String.join("\n", parts);
    }

    private static String extractField(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 解码 JSON 字符串转义，含 \\uXXXX（百度响应中的中文以 \\u 形式返回） */
    private static String decodeJsonString(String s) {
        StringBuilder sb = new StringBuilder();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= n) {
                sb.append(c);
                i++;
                continue;
            }
            char next = s.charAt(i + 1);
            switch (next) {
                case 'n': sb.append('\n'); i += 2; break;
                case 't': sb.append('\t'); i += 2; break;
                case 'r': sb.append('\r'); i += 2; break;
                case 'b': sb.append('\b'); i += 2; break;
                case 'f': sb.append('\f'); i += 2; break;
                case '"': sb.append('"'); i += 2; break;
                case '/': sb.append('/'); i += 2; break;
                case '\\': sb.append('\\'); i += 2; break;
                case 'u':
                    if (i + 5 < n) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                            i += 6;
                        } catch (NumberFormatException ex) {
                            sb.append('\\');
                            i += 2;
                        }
                    } else {
                        sb.append('\\');
                        i += 2;
                    }
                    break;
                default:
                    sb.append('\\');
                    i += 2;
            }
        }
        return sb.toString();
    }

    private static String md5Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
