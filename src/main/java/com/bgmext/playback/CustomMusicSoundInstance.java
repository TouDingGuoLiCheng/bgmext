package com.bgmext.playback;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 代表一首正在播放的音乐（自定义文件或某一首具体的原版曲子）。
 *
 * 几个关键点：
 * 1. **relative = true**：和原版音乐一致。若为 false，声音会被当成世界坐标
 *    (0,0,0) 的定点音源，于是跟着视角左右偏。
 * 2. 音效位置在若干槽位间**轮换**：模组包里的 Audio Improvements 会拦截
 *    "防止重复播放同一首音乐"，位置固定的话它挑 100 次也挑不出不同的。
 * 3. 音效本体注册在 assets/bgmext/sounds.json，占位 ogg 是 0 字节、永远不会被读，
 *    真正的 PCM 由 SoundBufferLibraryMixin 在 getStream 阶段提供
 *    （来源可能是玩家文件，也可能是原版资源）。
 */
public class CustomMusicSoundInstance extends AbstractSoundInstance {

    /** 轮换槽位数，需与 sounds.json 中的条目数一致 */
    public static final int SLOTS = 8;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** 显示用标签（曲名或原版文件名） */
    private final String label;

    public CustomMusicSoundInstance(String label, float volumeGain) {
        this(label, nextLocation(), volumeGain);
    }

    public CustomMusicSoundInstance(String label, ResourceLocation location, float volumeGain) {
        super(location, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
        this.label = label;
        this.volume = volumeGain;
        this.pitch = 1.0f;
        // 原版音乐就是这样设置的：相对听者，永远居中
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
    }

    private static ResourceLocation nextLocation() {
        int slot = Math.floorMod(COUNTER.getAndIncrement(), SLOTS);
        return new ResourceLocation(CustomAudioRegistry.NAMESPACE, "custom_music_" + slot);
    }

    public String getLabel() {
        return label;
    }
}