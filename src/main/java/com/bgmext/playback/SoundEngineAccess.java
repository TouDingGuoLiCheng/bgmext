package com.bgmext.playback;

import net.minecraft.client.sounds.SoundEngine;

/**
 * 由 SoundManager 的 Mixin 实现（duck interface）。
 *
 * 原先用反射读 SoundManager.soundEngine，但 ForgeGradle 的 reobf 只重写字节码引用，
 * **不会改字符串字面量**，所以生产环境里字段名已变成 SRG 名，`getDeclaredField("soundEngine")`
 * 必然抛 NoSuchFieldException。改用 Mixin 的 @Shadow，refmap 会自动完成名字映射。
 */
public interface SoundEngineAccess {

    SoundEngine bgmext$soundEngine();
}