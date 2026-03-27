package com.zhiyin.plugins.microservices;

import org.jetbrains.annotations.NotNull;

/**
 * Small utility to combine class-level and method-level paths into a final path.
 */
public final class PathUtils {

    private PathUtils() {}

    public static String combine(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String left = a.trim();
        String right = b.trim();
        if (left.endsWith("/")) left = left.substring(0, left.length() - 1);
        if (!right.startsWith("/")) right = "/" + right;
        if (left.isEmpty()) {
            return right.equals("/") ? "/" : right;
        } else {
            return left + right;
        }
    }

    public static String normalize(@NotNull String path) {
        // collapse multiple slashes, ensure starts with /
        String p = path.replaceAll("/{2,}", "/");
        if (!p.startsWith("/")) p = "/" + p;
        // remove trailing slash if not root
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
