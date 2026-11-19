package com.bgmext.playback;

import net.minecraft.client.resources.sounds.SoundInstance;

import javax.annotation.Nullable;

/**
 * 由 MusicManager 的 Mixin 实现（duck interface）。
 *
 * 让本模组能和原版 MusicManager 共享状态：
 * - 读取原版当前正在播的实例，播放前先停掉，避免两首叠在一起
 * - 把「手动播放的原版曲目」登记回原版字段，这样原版不会以为没音乐而再启一首
 */
public interface MusicManagerDuck {

    @Nullable
    SoundInstance bgmext$currentMusic();

    /**
     * 把一个实例登记为原版「当前音乐」，并重置原版的下一首冷却。
     *
     * @param instance   正在播放的实例（可为 null 表示清空）
     * @param delayTicks 距下一首选曲要等的 tick 数
     */
    void bgmext$adopt(@Nullable SoundInstance instance, int delayTicks);
}