package com.bgmext.scanner;

import com.bgmext.BgmExtension;
import com.bgmext.MusicCategory;
import com.bgmext.config.MusicConfig;
import com.bgmext.metadata.AudioMetadata;
import com.bgmext.metadata.MetadataReader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class MusicLibrary {

    private static final Map<MusicCategory, List<Track>> library = new LinkedHashMap<>();
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("ogg", "mp3", "wav", "flac");

    static {
        for (MusicCategory cat : MusicCategory.values()) {
            library.put(cat, new ArrayList<>());
        }
    }

    public static void reload() {
        long start = System.currentTimeMillis();
        for (List<Track> tracks : library.values()) {
            tracks.clear();
        }

        Path musicRoot = MusicConfig.getMusicDir();
        if (!Files.isDirectory(musicRoot)) {
            try {
                Files.createDirectories(musicRoot);
            } catch (IOException ignored) {
                return;
            }
        }

        for (MusicCategory category : MusicCategory.values()) {
            Path categoryDir = musicRoot.resolve(category.getDirName());
            if (!Files.isDirectory(categoryDir)) continue;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(categoryDir, p ->
                    Files.isRegularFile(p) && SUPPORTED_EXTENSIONS.contains(getExtension(p)))) {
                for (Path file : stream) {
                    try {
                        AudioMetadata meta = MetadataReader.read(file);
                        library.get(category).add(new Track(file, meta));
                    } catch (Throwable t) {
                        // 单个文件出问题不能让整轮扫描失败
                        BgmExtension.LOGGER.warn("[bgmext] 跳过无法读取的文件: {}", file, t);
                    }
                }
            } catch (Throwable t) {
                BgmExtension.LOGGER.warn("[bgmext] 扫描目录失败: {}", categoryDir, t);
            }
        }

        long total = library.values().stream().mapToLong(List::size).sum();
        BgmExtension.LOGGER.info("[bgmext] 扫描完成: {} 首自定义音乐 (耗时 {} ms)", total, System.currentTimeMillis() - start);
    }

    public static List<Track> getTracks(MusicCategory category) {
        return Collections.unmodifiableList(library.getOrDefault(category, List.of()));
    }

    public static Map<MusicCategory, List<Track>> getAllTracks() {
        return Collections.unmodifiableMap(library);
    }

    public static List<Track> getEnabledTracks(MusicCategory category) {
        return getTracks(category).stream()
                .filter(t -> MusicConfig.isCustomEnabled(category, t.getFileName()))
                .toList();
    }

    private static String getExtension(Path path) {
        String name = path.getFileName().toString();
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1).toLowerCase() : "";
    }
}