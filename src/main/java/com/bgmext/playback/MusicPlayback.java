package com.bgmext.playback;

import com.bgmext.MusicCategory;
import com.bgmext.config.MusicConfig;
import com.bgmext.scanner.MusicLibrary;
import com.bgmext.scanner.Track;
import com.bgmext.vanilla.VanillaMusicRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.Music;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 选曲与播放。
 *
 * 池子 = 「该场景原版音乐条目下的启用文件」+「该分类启用的自定义音乐」，
 * **每个文件占一个名额**，均匀抽取。
 *
 * 为什么按文件而不是按条目：
 * 原版一个 SoundEvent 背后可能有几十个 ogg（music.game 就有 25 个）。
 * 若按条目加权，原版整体只占 1/(N自定义+1)，每首原版曲子的出场率
 * 会被压到自定义曲子的几十分之一。
 *
 * 播放时我们指定**具体某一首**（通过 bgmext 的合成音效位置 + getStream 拦截），
 * 而不是让引擎在那个条目的所有文件里自己挑——这样按文件粒度的启用/禁用才有意义。
 */
public final class MusicPlayback {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Random RANDOM = new Random();

    /** 一首播完后到下一位的间隔 */
    private static final int GAP_TICKS = 20 * 20;
    /** 池子为空时的重试间隔 */
    private static final int EMPTY_RETRY_TICKS = 20 * 10;

    @Nullable
    private static SoundInstance current;
    private static int remainingTicks;
    @Nullable
    private static String statusText;

    /** 曾经启动过的实例：有的实例在 stopAll 之后才真正开始发声，需要再停一遍 */
    private static final List<SoundInstance> STARTED = new ArrayList<>();
    private static final int STARTED_KEEP = 16;

    private MusicPlayback() {
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /** 由 Mixin 每个 tick 调用 */
    public static void tick(Minecraft mc, MusicCategory category) {
        // 还在响就等它（比用时长估算靠谱）
        if (current != null && isActive(mc, current)) {
            return;
        }
        if (remainingTicks > 0) {
            remainingTicks--;
            return;
        }
        playRandom(mc, category);
    }

    /** 界面「立即播放」：无视冷却，立刻抽一首 */
    public static boolean playRandomNow(Minecraft mc, MusicCategory category) {
        remainingTicks = 0;
        return playRandom(mc, category);
    }

    @Nullable
    public static String statusText() {
        return statusText;
    }

    /** 停掉本模组播过的所有实例 + 原版正在播的音乐，并清空状态 */
    public static void stopAll(Minecraft mc) {
        if (current != null) {
            safeStop(mc, current);
            current = null;
        }
        for (SoundInstance instance : STARTED) {
            safeStop(mc, instance);
        }
        STARTED.clear();

        MusicManager mm = mc.getMusicManager();
        if (mm instanceof MusicManagerDuck duck) {
            SoundInstance vanillaCurrent = duck.bgmext$currentMusic();
            if (vanillaCurrent != null) {
                safeStop(mc, vanillaCurrent);
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static boolean playRandom(Minecraft mc, MusicCategory category) {
        Music situation = mc.getSituationalMusic();
        if (situation == null) {
            // 该场景本来就没有音乐（例如洞穴里）—— 不要自作主张放歌
            stopAll(mc);
            remainingTicks = 20 * 5;
            statusText = "\u6b64\u573a\u666f\u672c\u6765\u5c31\u6ca1\u6709\u97f3\u4e50";
            return false;
        }

        ResourceLocation eventId = eventIdOf(situation);
        List<ResourceLocation> allFiles = eventId == null
                ? List.of() : VanillaMusicRegistry.filesOf(eventId);
        List<ResourceLocation> enabledFiles = new ArrayList<>();
        for (ResourceLocation file : allFiles) {
            if (MusicConfig.isVanillaEnabled(file)) {
                enabledFiles.add(file);
            }
        }

        List<Track> customs = MusicLibrary.getEnabledTracks(category);

        List<Object> pool = new ArrayList<>(customs);

        // 兜底：注册表里查不到这个条目的文件信息时（模组包覆盖了 sounds.json 等），
        // 退回「整条目」播放——权重要差一些，但至少原版音乐不会整个被挤出池子。
        Object fallback = null;
        if (allFiles.isEmpty()) {
            fallback = vanillaEventInstance(situation);
            if (fallback != null) {
                pool.add(fallback);
            }
        } else {
            pool.addAll(enabledFiles);
        }

        LOGGER.info("[bgmext] 池子构成 (分类 {}): 自定义 {} 首, 原版 {}/{} 首 (条目 {}){}",
                category.getDirName(), customs.size(),
                enabledFiles.size(), allFiles.size(), eventId,
                fallback != null ? " [兜底:整条目]" : "");

        stopAll(mc);

        if (pool.isEmpty()) {
            remainingTicks = EMPTY_RETRY_TICKS;
            statusText = "\u6b64\u5206\u7c7b\u6ca1\u6709\u53ef\u64ad\u653e\u7684\u66f2\u76ee";
            return false;
        }

        Object chosen = pool.get(RANDOM.nextInt(pool.size()));
        if (chosen instanceof Track track) {
            return playCustom(mc, track);
        }
        if (chosen instanceof ResourceLocation file) {
            return playVanillaFile(mc, file);
        }
        return playRawInstance(mc, (SoundInstance) chosen);
    }

    /** 兜底路径：直接把原版条目交给引擎（由引擎在其内部文件里随机挑） */
    private static boolean playRawInstance(Minecraft mc, SoundInstance instance) {
        try {
            mc.getSoundManager().play(instance);
        } catch (Throwable t) {
            LOGGER.error("[bgmext] 播放原版条目失败: {}", instance.getLocation(), t);
            remainingTicks = 20 * 5;
            return false;
        }
        current = instance;
        remember(instance);
        remainingTicks = GAP_TICKS;
        statusText = "\u25b6 \u6b63\u5728\u64ad\u653e\uff08\u539f\u7248\u6761\u76ee\uff09\uff1a" + instance.getLocation();
        LOGGER.info("[bgmext] 播放原版条目: {}", instance.getLocation());
        return true;
    }

    /** 把「原版当前情境音乐」包成可直接播放的实例 */
    @Nullable
    private static SoundInstance vanillaEventInstance(Music music) {
        try {
            net.minecraft.sounds.SoundEvent event = music.getEvent().value();
            return new net.minecraft.client.resources.sounds.SimpleSoundInstance(
                    event.getLocation(),
                    net.minecraft.sounds.SoundSource.MUSIC,
                    1.0f, 1.0f,
                    SoundInstance.createUnseededRandom(),
                    false, 0,
                    SoundInstance.Attenuation.NONE,
                    0.0, 0.0, 0.0,
                    true);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean playCustom(Minecraft mc, Track track) {
        CustomAudioRegistry.setPending(CustomAudioRegistry.Source.of(track.getFilePath()));
        return start(mc, track.getDisplayTitle(), MusicConfig.customGain());
    }

    private static boolean playVanillaFile(Minecraft mc, ResourceLocation file) {
        CustomAudioRegistry.setPending(CustomAudioRegistry.Source.of(file));
        return start(mc, VanillaMusicRegistry.shortName(file), 1.0f);
    }

    private static boolean start(Minecraft mc, String label, float gain) {
        SoundEngine engine = soundEngine(mc);
        if (engine == null) {
            remainingTicks = 20 * 5;
            statusText = "\u65e0\u6cd5\u8bbf\u95ee\u58f0\u97f3\u5f15\u64ce";
            return false;
        }

        CustomMusicSoundInstance instance = new CustomMusicSoundInstance(label, gain);
        try {
            engine.play(instance);
        } catch (Throwable t) {
            LOGGER.error("[bgmext] 播放失败: {}", label, t);
            remainingTicks = 20 * 5;
            statusText = "\u64ad\u653e\u5931\u8d25\uff1a" + label;
            return false;
        }

        current = instance;
        remember(instance);
        remainingTicks = GAP_TICKS;
        statusText = "\u25b6 \u6b63\u5728\u64ad\u653e\uff1a" + label;
        LOGGER.info("[bgmext] 播放: {}", label);
        return true;
    }

    private static void remember(SoundInstance instance) {
        STARTED.add(instance);
        while (STARTED.size() > STARTED_KEEP) {
            STARTED.remove(0);
        }
    }

    private static void safeStop(Minecraft mc, SoundInstance instance) {
        try {
            mc.getSoundManager().stop(instance);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isActive(Minecraft mc, SoundInstance instance) {
        try {
            return mc.getSoundManager().isActive(instance);
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    private static ResourceLocation eventIdOf(Music music) {
        try {
            return music.getEvent().value().getLocation();
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static SoundEngine soundEngine(Minecraft mc) {
        SoundManager sm = mc.getSoundManager();
        if (sm instanceof SoundEngineAccess access) {
            return access.bgmext$soundEngine();
        }
        LOGGER.error("[bgmext] SoundManager 未挂载 SoundEngineAccess（Mixin 未生效？）");
        return null;
    }
}