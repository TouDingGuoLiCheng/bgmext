package com.bgmext.playback;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.nio.file.Path;

/**
 * 连接「音效请求」和「音频来源」的小注册表。
 *
 * 原版引擎播放时会向 SoundBufferLibrary 要 `bgmext:*` 的音频流，
 * 但那个位置并不是资源包里的文件。我们在这里记下「这次该解码什么」，
 * 由 SoundBufferLibraryMixin 取用。
 *
 * 来源有两种：
 * - 玩家自定义文件（Path）
 * - 原版音乐文件（ResourceLocation，如 minecraft:sounds/music/game/hal3.ogg）
 *
 * 这样播「某一首具体的原版曲子」也走同一条路，于是可以按文件粒度启用/禁用。
 */
public final class CustomAudioRegistry {

    public static final String NAMESPACE = "bgmext";

    public record Source(@Nullable Path file, @Nullable ResourceLocation resource) {

        public static Source of(Path file) {
            return new Source(file, null);
        }

        public static Source of(ResourceLocation resource) {
            return new Source(null, resource);
        }

        public String describe() {
            if (file != null) return file.getFileName().toString();
            return resource != null ? resource.toString() : "?";
        }
    }

    @Nullable
    private static volatile Source pending;

    private CustomAudioRegistry() {
    }

    public static void setPending(@Nullable Source source) {
        pending = source;
    }

    @Nullable
    public static Source pending() {
        return pending;
    }

    /** 这个音效位置是不是本模组伪造的（命名空间为 bgmext 即可认定） */
    public static boolean isOurs(@Nullable ResourceLocation location) {
        return location != null && NAMESPACE.equals(location.getNamespace());
    }
}