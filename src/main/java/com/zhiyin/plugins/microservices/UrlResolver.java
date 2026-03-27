package com.zhiyin.plugins.microservices;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

public final class UrlResolver {

    private static final Pattern MULTI_SLASH = Pattern.compile("/{2,}");
    private static final Pattern DOT_SEGMENT = Pattern.compile("(^|/)(\\./|\\.\\./)+");

    private UrlResolver() {}

    /**
     * 将 ../, ./ 等路径段解析成“近似绝对路径”
     * （在 HTML/JS 的相对路径上下文里，我们无法得到真实物理路径，但可逻辑规范化）
     */
    @NotNull
    public static String normalize(@NotNull String raw) {
        String url = raw.trim();

        // 去除查询参数与 #anchor
        int qIdx = url.indexOf('?');
        if (qIdx > 0) url = url.substring(0, qIdx);
        int hashIdx = url.indexOf('#');
        if (hashIdx > 0) url = url.substring(0, hashIdx);

        // 替换反斜杠为正斜杠
        url = url.replace('\\', '/');

        // 去掉 ./ 和 ../ 段
        url = DOT_SEGMENT.matcher(url).replaceAll("/");

        // 合并重复的斜杠
        url = MULTI_SLASH.matcher(url).replaceAll("/");

        // 确保以 / 开头
        if (!url.startsWith("/")) {
            url = "/" + url;
        }

        // 去掉结尾 /
        if (url.length() > 1 && url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        return url;
    }

    /**
     * 模糊匹配：
     * - 忽略大小写
     * - 支持 partial match，如 ../Basic/xxxService -> /basic/xxxservice/
     */
    public static boolean fuzzyMatch(@NotNull String raw, @NotNull String candidate) {
        String normRaw = normalize(raw);
        String normCandidate = normalize(candidate);
        // 完全匹配 或 末尾匹配（较宽松）
        return normRaw.equals(normCandidate) || normRaw.endsWith(normCandidate) || normCandidate.endsWith(normRaw);
    }

    /**
     * 模糊匹配：
     * - 忽略大小写
     * - 支持 partial match，如 ../Basic/xxxService -> /basic/xxxservice/
     * - 支持 contains match，如 ../Basic/xxxService -> /basic/xxxservice/
     */
    public static boolean fuzzyMatchWithContains(@NotNull String raw, @NotNull String candidate) {
        String normRaw = normalize(raw);
        String normCandidate = normalize(candidate);
        // 完全匹配 或 末尾匹配（较宽松）或包含匹配
        return fuzzyMatch(raw, candidate) || normRaw.contains(normCandidate) || normCandidate.contains(normRaw);
    }
}
