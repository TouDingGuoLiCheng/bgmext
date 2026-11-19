package com.bgmext.config;

import com.bgmext.BgmExtension;
import com.bgmext.MusicCategory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class MusicConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ConfigData data;
    private static Path configFile;

    /** 懒加载访问器：任何入口调用都不会拿到 null */
    private static ConfigData data() {
        if (data == null) {
            load();
        }
        return data;
    }

    private static Path configFile() {
        if (configFile == null) {
            configFile = getConfigDir().resolve("config.json");
        }
        return configFile;
    }

    public static ConfigData load() {
        if (data != null) return data;

        Path file = configFile();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                ConfigData loaded = GSON.fromJson(r, ConfigData.class);
                data = (loaded != null) ? loaded : new ConfigData();
            } catch (Exception e) {
                BgmExtension.LOGGER.warn("[bgmext] config.json 读取失败, 使用默认配置", e);
                data = new ConfigData();
            }
        } else {
            data = new ConfigData();
        }

        // 反序列化后 Map 字段可能为 null, 统一兜底
        if (data.vanillaTracks == null) data.vanillaTracks = new HashMap<>();
        if (data.customTracks == null) data.customTracks = new HashMap<>();

        BgmExtension.LOGGER.info("[bgmext] 配置已加载: vanilla 分类={}, custom 分类={}",
                data.vanillaTracks.size(), data.customTracks.size());
        return data;
    }

    public static void save() {
        ConfigData d = data();
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(d, w);
            }
        } catch (IOException e) {
            BgmExtension.LOGGER.error("[bgmext] config.json 保存失败", e);
        }
    }

    public static Path getConfigDir() {
        return Path.of("config", BgmExtension.MOD_ID);
    }

    public static Path getMusicDir() {
        return getConfigDir().resolve("music");
    }

    public static boolean isVanillaEnabled(ResourceLocation soundId) {
        Map<String, Boolean> flat = new HashMap<>();
        for (Map<String, Boolean> m : data().vanillaTracks.values()) {
            if (m != null) flat.putAll(m);
        }
        Boolean v = flat.get(soundId.toString());
        return v == null || v; // 默认启用
    }

    public static void setVanillaEnabled(String category, ResourceLocation soundId, boolean enabled) {
        data().vanillaTracks
                .computeIfAbsent(category, k -> new HashMap<>())
                .put(soundId.toString(), enabled);
        save();
    }

    public static boolean isCustomEnabled(MusicCategory category, String fileName) {
        Map<String, ConfigData.TrackConfig> tracks = data().customTracks.get(category.getDirName());
        if (tracks == null) return true; // 默认启用
        ConfigData.TrackConfig tc = tracks.get(fileName);
        return tc == null || tc.enabled;
    }

    public static void setCustomEnabled(MusicCategory category, String fileName, boolean enabled) {
        data().customTracks
                .computeIfAbsent(category.getDirName(), k -> new HashMap<>())
                .put(fileName, new ConfigData.TrackConfig(enabled));
        save();
    }

    public static void deleteCustomTrack(MusicCategory category, String fileName) {
        Map<String, ConfigData.TrackConfig> tracks = data().customTracks.get(category.getDirName());
        if (tracks != null) {
            tracks.remove(fileName);
        }
        save();
    }

    // ---- 自定义音乐音量 ----

    public static final float DEFAULT_VOLUME_DB = -15f;
    public static final float MIN_VOLUME_DB = -60f;
    public static final float MAX_VOLUME_DB = 6f;

    /** 自定义音乐的增益（分贝） */
    public static float getCustomVolumeDb() {
        float db = data().customMusicVolumeDb;
        if (Float.isNaN(db) || db < MIN_VOLUME_DB || db > MAX_VOLUME_DB) {
            db = DEFAULT_VOLUME_DB;
            data().customMusicVolumeDb = db;
        }
        return db;
    }

    /** 只改内存值，由 onClose 统一落盘（拖动滑块时不该每格都写文件） */
    public static void setCustomVolumeDb(float db) {
        float clamped = Math.max(MIN_VOLUME_DB, Math.min(MAX_VOLUME_DB, db));
        data().customMusicVolumeDb = clamped;
    }

    /** 分贝 → 线性增益，给 SoundInstance.volume 用 */
    public static float customGain() {
        return (float) Math.pow(10.0, getCustomVolumeDb() / 20.0);
    }
}