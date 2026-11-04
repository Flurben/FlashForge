package com.flurben.flashforge.compat;

import java.util.Collections;
import java.util.Map;

/** Placeholder until the DH integration lands. */
public final class DistantHorizonsCompat {
    private DistantHorizonsCompat() {}

    public static Map<String, String> dimensionPaths() {
        return Collections.emptyMap();
    }

    public static void onReplayClosed() {
    }
}
