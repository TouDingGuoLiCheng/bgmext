package com.bgmext.config;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MusicConfigTest {

    @Test
    void loadReturnsNonNull() {
        ConfigData data = MusicConfig.load();
        assertNotNull(data);
    }

    @Test
    void configDirPointsToConfig() {
        Path dir = MusicConfig.getConfigDir();
        assertTrue(dir.endsWith(Path.of("config", "bgmext")));
    }

    @Test
    void musicDirPointsUnderConfig() {
        Path dir = MusicConfig.getMusicDir();
        assertTrue(dir.endsWith(Path.of("config", "bgmext", "music")));
    }
}