package com.bgmext.mixin;

import com.bgmext.MusicCategory;
import com.bgmext.playback.MusicManagerDuck;
import com.bgmext.playback.MusicPlayback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * 播放调度的接入点（薄适配层）。
 *
 * 选曲/播放逻辑都在 {@link MusicPlayback} 里，这样界面上的「立即播放」可以直接调用。
 *
 * 接管条件：**当前场景有情境音乐**。此时由本模组按文件粒度选曲
 * （这样才能按文件启用/禁用原版曲子，并且权重按文件数计算）。
 * 若场景本来就没有音乐（例如洞穴深处），完全交回原版、并且不自己放歌。
 */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin implements MusicManagerDuck {

    @Shadow @Final private Minecraft minecraft;

    @Shadow @Nullable private SoundInstance currentMusic;

    @Override
    @Nullable
    public SoundInstance bgmext$currentMusic() {
        return this.currentMusic;
    }

    @Override
    public void bgmext$adopt(@Nullable SoundInstance instance, int delayTicks) {
        this.currentMusic = instance;
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void bgmext$tick(CallbackInfo ci) {
        Minecraft mc = this.minecraft;

        if (mc.getSituationalMusic() == null) {
            // 该场景本来就没有音乐：交回原版，但先把我方播的收掉，避免叠音
            MusicPlayback.stopAll(mc);
            return;
        }

        ci.cancel();
        MusicPlayback.tick(mc, MusicCategory.fromPlayerState(mc.player, mc));
    }
}