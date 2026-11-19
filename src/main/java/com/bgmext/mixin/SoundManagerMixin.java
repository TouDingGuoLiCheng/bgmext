package com.bgmext.mixin;

import com.bgmext.playback.SoundEngineAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 让本模组能拿到 SoundManager 内部的 SoundEngine。
 *
 * 用 @Shadow 而不是反射：reobf 不会重写反射用的字符串，
 * 在生产环境里反射必然找不到字段。
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin implements SoundEngineAccess {

    @Shadow @Final private SoundEngine soundEngine;

    @Override
    public SoundEngine bgmext$soundEngine() {
        return this.soundEngine;
    }
}