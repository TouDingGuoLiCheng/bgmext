package com.bgmext.scanner;

import com.bgmext.metadata.AudioMetadata;
import java.nio.file.Path;

public class Track {

    private final Path filePath;
    private final AudioMetadata metadata;

    public Track(Path filePath, AudioMetadata metadata) {
        this.filePath = filePath;
        this.metadata = metadata;
    }

    public Path getFilePath() {
        return filePath;
    }

    public String getFileName() {
        return filePath.getFileName().toString();
    }

    public AudioMetadata getMetadata() {
        return metadata;
    }

    public String getFormattedDuration() {
        int total = (int) metadata.durationSeconds();
        int min = total / 60;
        int sec = total % 60;
        return String.format("%d:%02d", min, sec);
    }

    public String getDisplayTitle() {
        String t = metadata.title();
        if (t != null && !t.isBlank() && !t.equals(AudioMetadata.fallback(getFileName()).title())) {
            return t;
        }
        return getFileName();
    }

    /** 列表显示的副标题（作者）。没有标签信息时返回 null，由界面决定不显示 */
    public String getDisplaySubtitle() {
        String a = metadata.artist();
        if (a != null && !a.isBlank() && !a.equals("Unknown")) {
            return a;
        }
        return null;
    }

    @Override
    public String toString() {
        return getDisplayTitle();
    }
}