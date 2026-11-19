package com.bgmext.metadata;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

class MetadataReaderTest {

    @Test
    void fallbackForUnknownExtension() {
        AudioMetadata meta = MetadataReader.read(Path.of("nonexistent.xyz"));
        assertEquals("nonexistent", meta.title());
        assertEquals("Unknown", meta.artist());
        assertEquals(0f, meta.durationSeconds());
    }

    @Test
    void fallbackForNonExistentFile() {
        AudioMetadata meta = MetadataReader.read(Path.of("does_not_exist.ogg"));
        assertEquals("does_not_exist", meta.title());
    }

    @Test
    void fallbackTitleFromFileName() {
        AudioMetadata fallback = AudioMetadata.fallback("battle_theme.mp3");
        assertEquals("battle_theme", fallback.title());
        assertEquals("Unknown", fallback.artist());
    }

    @Test
    void fallbackTitleNoExtension() {
        AudioMetadata fallback = AudioMetadata.fallback("mysong");
        assertEquals("mysong", fallback.title());
    }
}