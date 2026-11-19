package com.bgmext.metadata;

import com.mojang.logging.LogUtils;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.flac.FlacFileReader;
import org.jaudiotagger.audio.mp3.MP3File;
import org.jaudiotagger.tag.FieldKey;
import org.slf4j.Logger;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Path;

/**
 * 读取音频文件的标题 / 作者 / 时长。
 *
 * 设计原则：**绝不因为某个文件有问题就崩游戏**。
 * 所以这里捕获 Throwable（包括缺库时的 NoClassDefFoundError），
 * 任何异常都退化成「用文件名当标题」。
 */
public class MetadataReader {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 缺库检测只做一次，避免每个文件都刷一遍错误 */
    private static Boolean jaudiotaggerAvailable;

    public static AudioMetadata read(Path file) {
        String fileName = file.getFileName().toString();
        String lower = fileName.toLowerCase();

        try {
            if (lower.endsWith(".ogg")) return readOgg(file);
            if (lower.endsWith(".mp3")) return readMp3(file);
            if (lower.endsWith(".wav")) return readWav(file);
            if (lower.endsWith(".flac")) return readFlac(file);
        } catch (Throwable t) {
            LOGGER.warn("[bgmext] 读取元数据失败, 回退为文件名: {} ({})", fileName, t.toString());
        }

        return AudioMetadata.fallback(fileName);
    }

    private static boolean jaudiotaggerAvailable() {
        if (jaudiotaggerAvailable == null) {
            try {
                Class.forName("org.jaudiotagger.audio.AudioFileIO", false, MetadataReader.class.getClassLoader());
                jaudiotaggerAvailable = Boolean.TRUE;
            } catch (Throwable t) {
                LOGGER.error("[bgmext] JAudioTagger 不可用, mp3/flac 将只显示文件名 (依赖未打进 JAR?)");
                jaudiotaggerAvailable = Boolean.FALSE;
            }
        }
        return jaudiotaggerAvailable;
    }

    private static String titleOrFallback(String tagValue, String fileName) {
        return (tagValue != null && !tagValue.isBlank())
                ? tagValue
                : AudioMetadata.fallback(fileName).title();
    }

    private static String artistOrUnknown(String tagValue) {
        return (tagValue != null && !tagValue.isBlank()) ? tagValue : "Unknown";
    }

    // ------------------------------------------------------------------

    private static AudioMetadata readOgg(Path file) throws Exception {
        if (!jaudiotaggerAvailable()) return AudioMetadata.fallback(file.getFileName().toString());

        org.jaudiotagger.audio.ogg.OggFileReader reader = new org.jaudiotagger.audio.ogg.OggFileReader();
        org.jaudiotagger.audio.AudioFile af = reader.read(file.toFile());
        String fileName = file.getFileName().toString();

        String title = null;
        String artist = null;
        if (af.getTag() != null) {
            title = af.getTag().getFirst(FieldKey.TITLE);
            artist = af.getTag().getFirst(FieldKey.ARTIST);
        }
        int duration = af.getAudioHeader() != null ? af.getAudioHeader().getTrackLength() : 0;

        return new AudioMetadata(titleOrFallback(title, fileName),
                artistOrUnknown(artist), duration);
    }

    private static AudioMetadata readMp3(Path file) throws Exception {
        if (!jaudiotaggerAvailable()) return AudioMetadata.fallback(file.getFileName().toString());

        MP3File mp3 = (MP3File) AudioFileIO.read(file.toFile());
        String fileName = file.getFileName().toString();

        String title = null;
        String artist = null;
        if (mp3.getTag() != null) {
            title = mp3.getTag().getFirst(FieldKey.TITLE);
            artist = mp3.getTag().getFirst(FieldKey.ARTIST);
        }
        int duration = mp3.getAudioHeader() != null ? mp3.getAudioHeader().getTrackLength() : 0;

        return new AudioMetadata(titleOrFallback(title, fileName),
                artistOrUnknown(artist), duration);
    }

    private static AudioMetadata readWav(Path file) throws Exception {
        String fileName = file.getFileName().toString();
        // wav 没有标准歌曲标签, 但时长可以算出来
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(file.toFile())) {
            long frames = ais.getFrameLength();
            float rate = ais.getFormat().getFrameRate();
            float seconds = rate > 0f ? frames / rate : 0f;
            return new AudioMetadata(AudioMetadata.fallback(fileName).title(), "Unknown", seconds);
        }
    }

    private static AudioMetadata readFlac(Path file) throws Exception {
        if (!jaudiotaggerAvailable()) return AudioMetadata.fallback(file.getFileName().toString());

        FlacFileReader reader = new FlacFileReader();
        org.jaudiotagger.audio.AudioFile af = reader.read(file.toFile());
        String fileName = file.getFileName().toString();

        String title = null;
        String artist = null;
        if (af.getTag() != null) {
            title = af.getTag().getFirst(FieldKey.TITLE);
            artist = af.getTag().getFirst(FieldKey.ARTIST);
        }
        int duration = af.getAudioHeader() != null ? af.getAudioHeader().getTrackLength() : 0;

        return new AudioMetadata(titleOrFallback(title, fileName),
                artistOrUnknown(artist), duration);
    }
}