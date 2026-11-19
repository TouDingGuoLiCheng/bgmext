package com.bgmext.metadata;

public record AudioMetadata(String title, String artist, float durationSeconds) {

    public static AudioMetadata fallback(String fileName) {
        String name = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;
        return new AudioMetadata(name, "Unknown", 0f);
    }
}