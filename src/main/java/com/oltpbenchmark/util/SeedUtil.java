package com.oltpbenchmark.util;

public final class SeedUtil {
  private SeedUtil() {}

  public static long baseSeed() {
    return Long.getLong("benchbase.seed", 12345L);
  }

  public static long derive(String tag) {
    return mix64(baseSeed() ^ hashString64(tag));
  }

  public static long derive(String tag, long extra) {
    return mix64(baseSeed() ^ hashString64(tag) ^ extra);
  }

  private static long hashString64(String s) {
    long h = 1125899906842597L;
    for (int i = 0; i < s.length(); i++) {
      h = 31L * h + s.charAt(i);
    }
    return h;
  }

  private static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }
}
