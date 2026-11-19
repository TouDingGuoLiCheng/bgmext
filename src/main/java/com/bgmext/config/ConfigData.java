package com.bgmext.config;

import java.util.HashMap;
import java.util.Map;

public class ConfigData {
    public Map<String, Map<String, Boolean>> vanillaTracks = new HashMap<>();
    public Map<String, Map<String, TrackConfig>> customTracks = new HashMap<>();

    /** 自定义音乐的增益（分贝）。默认 -15 dB：自定义曲目通常比原版响得多 */
    public float customMusicVolumeDb = -15f;

    public static class TrackConfig {
        public boolean enabled = true;

        public TrackConfig() {}

        public TrackConfig(boolean enabled) {
            this.enabled = enabled;
        }
    }
}