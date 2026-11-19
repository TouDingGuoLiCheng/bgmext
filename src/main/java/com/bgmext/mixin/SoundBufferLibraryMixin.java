package com.bgmext.mixin;

import com.bgmext.playback.AudioDecoders;
import com.bgmext.playback.CustomAudioRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * 把自定义音乐的解码流喂给原版声音引擎。
 *
 * 原版 getStream 只从资源包读文件、且写死 OggAudioStream，所以 mp3/wav/flac 走不通。
 * 这里在 HEAD 拦截：凡是 bgmext 命名空间的请求，直接返回我们自己解码的 PCM 流。
 *
 * 返回的是**流式**解码器（不是整首缓冲）：一首曲子解成 PCM 接近 30 MB，
 * 整首缓存会直接 OutOfMemoryError，而且会把渲染线程卡住近一秒。
 */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {

    private static final Logger BGMExt$LOGGER = LogUtils.getLogger();

    @Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
    private void bgmext$getStream(ResourceLocation location, boolean looping,
                                  CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        if (!CustomAudioRegistry.isOurs(location)) {
            return;
        }

        CustomAudioRegistry.Source source = CustomAudioRegistry.pending();
        if (source == null) {
            BGMExt$LOGGER.warn("[bgmext] 收到 {} 的音频流请求，但没有待解码来源", location);
            return;
        }

        try {
            AudioStream stream = AudioDecoders.open(source);
            BGMExt$LOGGER.info("[bgmext] 提供音频流 {} -> {}", location, source.describe());
            cir.setReturnValue(CompletableFuture.completedFuture(stream));
        } catch (Throwable t) {
            BGMExt$LOGGER.error("[bgmext] 打开音频流失败: {}", source.describe(), t);
            // 交回原版处理，同时把失败原因留在日志里
        }
    }
}