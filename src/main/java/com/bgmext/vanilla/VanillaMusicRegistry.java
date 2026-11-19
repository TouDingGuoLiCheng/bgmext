package com.bgmext.vanilla;

import com.bgmext.BgmExtension;
import com.bgmext.MusicCategory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 原版音乐注册表：运行时解析客户端资源里的 assets/minecraft/sounds.json，
 * 拿到「SoundEvent → 它包含的 ogg 文件」的**真实映射**。
 *
 * 为什么必须这样做：
 * - 原版一个 SoundEvent（如 music.game）背后其实是 25 个 ogg 文件，
 *   引擎播放时在它们之间随机挑一个。只看 SoundEvent 根本不知道有多少首，
 *   于是「原版 1 个名额 vs 自定义 7 个名额」权重严重失衡。
 * - 之前界面里硬编码的 music.game.hal3 之类根本不是注册 ID，开关点了也没用。
 *
 * 有了这份映射，权重、界面列表、单曲开关三件事才能同时做对。
 */
public final class VanillaMusicRegistry {

    private static volatile Map<ResourceLocation, List<ResourceLocation>> cache;

    private VanillaMusicRegistry() {
    }

    /** 资源重载后调用，下次访问重新解析 */
    public static void invalidate() {
        cache = null;
    }

    /** 全部原版音乐条目 → 其文件列表 */
    public static Map<ResourceLocation, List<ResourceLocation>> all() {
        Map<ResourceLocation, List<ResourceLocation>> snapshot = cache;
        if (snapshot == null) {
            snapshot = parse();
            cache = snapshot;
        }
        return snapshot;
    }

    public static List<ResourceLocation> filesOf(ResourceLocation eventId) {
        return all().getOrDefault(eventId, List.of());
    }

    /** 该分类下所有原版音乐条目（已排序） */
    public static List<ResourceLocation> eventsFor(MusicCategory category) {
        List<ResourceLocation> list = new ArrayList<>();
        for (ResourceLocation id : all().keySet()) {
            if (categoryOf(id) == category) {
                list.add(id);
            }
        }
        list.sort(Comparator.comparing(ResourceLocation::toString));
        return list;
    }

    /** 把原版音乐条目归到本模组的六分类里 */
    public static MusicCategory categoryOf(ResourceLocation eventId) {
        String p = eventId.getPath();
        if (p.equals("music.menu")) return MusicCategory.MENU;
        if (p.equals("music.creative")) return MusicCategory.CREATIVE;
        if (p.startsWith("music.nether")) return MusicCategory.NETHER;
        if (p.equals("music.end") || p.equals("music.dragon") || p.equals("music.credits")) {
            return MusicCategory.END;
        }
        if (p.startsWith("music.under_water")) return MusicCategory.UNDERWATER;
        // music.game 与 music.overworld.* 都属于主世界
        return MusicCategory.GAME;
    }

    /** sounds.json 里的 name → 真正的资源路径（sounds/xxx.ogg） */
    public static ResourceLocation toResourcePath(String name) {
        ResourceLocation id = name.contains(":")
                ? ResourceLocation.tryParse(name)
                : new ResourceLocation("minecraft", name);
        if (id == null) return null;

        String path = id.getPath();
        if (!path.endsWith(".ogg")) {
            path = path + ".ogg";
        }
        return new ResourceLocation(id.getNamespace(), "sounds/" + path);
    }

    /** 显示用短名：sounds/music/game/hal3.ogg → hal3 */
    public static String shortName(ResourceLocation file) {
        String path = file.getPath();
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        return base.endsWith(".ogg") ? base.substring(0, base.length() - 4) : base;
    }

    // ------------------------------------------------------------------

    private static Map<ResourceLocation, List<ResourceLocation>> parse() {
        Map<ResourceLocation, List<ResourceLocation>> map = new LinkedHashMap<>();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return map;

            ResourceLocation soundsJson = new ResourceLocation("minecraft", "sounds.json");

            // 关键：必须遍历**所有**资源包并合并。
            // getResource() 只给优先级最高的那一份，而整合包里那份很可能是某个模组的
            // 覆盖文件，原版那几十个文件根本不在里面。
            // 原版 SoundManager 自己也是把各包的 sounds.json 合并起来的。
            List<Resource> stack = mc.getResourceManager().getResourceStack(soundsJson);
            BgmExtension.LOGGER.info("[bgmext] sounds.json 出现在 {} 个资源包里", stack.size());

            int index = 0;
            for (Resource resource : stack) {
                index++;
                int entriesBefore = map.size();
                int filesBefore = totalFiles(map);
                try (BufferedReader reader = resource.openAsReader()) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                        String id = entry.getKey();
                        // 只要 music.*，music_disc.* 不算（它不以 "music." 开头）
                        if (!id.startsWith("music.")) continue;
                        if (!entry.getValue().isJsonObject()) continue;

                        JsonElement soundsEl = entry.getValue().getAsJsonObject().get("sounds");
                        if (soundsEl == null || !soundsEl.isJsonArray()) continue;

                        List<ResourceLocation> files = map.computeIfAbsent(
                                new ResourceLocation("minecraft", id), k -> new ArrayList<>());

                        JsonArray array = soundsEl.getAsJsonArray();
                        for (JsonElement el : array) {
                            String name = el.isJsonObject()
                                    ? (el.getAsJsonObject().has("name")
                                            ? el.getAsJsonObject().get("name").getAsString() : null)
                                    : el.getAsString();
                            if (name == null) continue;
                            ResourceLocation file = toResourcePath(name);
                            if (file != null && !files.contains(file)) {
                                files.add(file);
                            }
                        }
                    }
                } catch (Throwable t) {
                    BgmExtension.LOGGER.warn("[bgmext] 解析第 {} 个 sounds.json 失败: {}",
                            index, t.toString());
                }
                BgmExtension.LOGGER.info("[bgmext]   资源包 [{}]: 条目 {} -> {}, 文件 {} -> {}",
                        resource.sourcePackId(), entriesBefore, map.size(),
                        filesBefore, totalFiles(map));
            }

            // 去掉解析后没有任何文件的空条目
            map.entrySet().removeIf(e -> e.getValue().isEmpty());
        } catch (Throwable t) {
            BgmExtension.LOGGER.error("[bgmext] 解析 sounds.json 失败", t);
        }

        BgmExtension.LOGGER.info("[bgmext] 原版音乐注册表: {} 个条目, {} 个文件",
                map.size(), totalFiles(map));
        return map;
    }

    private static int totalFiles(Map<ResourceLocation, List<ResourceLocation>> map) {
        int total = 0;
        for (List<ResourceLocation> files : map.values()) {
            total += files.size();
        }
        return total;
    }
}