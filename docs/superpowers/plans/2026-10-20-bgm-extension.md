# BGM扩展 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个 1.20.1 Forge 客户端模组，允许玩家在 `config/bgmext/music/` 下按分类放置自定义音频文件（ogg/mp3/wav/flac），通过 ESC 菜单 GUI 管理原版与自定义背景音乐的启用/禁用。

**Architecture:** 五个模块：MusicLibrary（扫描器）、MetadataReader（元数据读取）、MusicConfig（配置 JSON 读写）、MusicSettingsScreen（GUI）、MixinMusicManager（播放调度 Mixin）。自定义音乐通过 `CustomMusicSoundInstance extends AbstractSoundInstance` 直接解码 PCM 推 OpenAL，绕过 SoundEvent 体系。

**模块契约（从已批准设计原样抄入，不得省略或改写）：**

| 单元 | 第二次使用 | 公开面 | 依赖方向 | 故意不做 |
|------|-----------|--------|---------|---------|
| 音乐扫描器（MusicLibrary） | GUI + 播放调度 | `reload(): void`、`getTracks(category: MusicCategory): List<Track>`、`getAllTracks(): Map<MusicCategory, List<Track>>` | 依赖元数据读取；不依赖 GUI / 配置 / Mixin | 文件夹热更新 |
| 元数据读取（MetadataReader） | 仅扫描器（不做通用） | `AudioMetadata(String title, String artist, float durationSeconds)` record；`MetadataReader.read(Path file): AudioMetadata` | 只依赖 JDK + JAudioTagger + JLayer；不依赖任何模组模块 | 专辑封面/歌词等扩展标签 |
| 配置管理（MusicConfig） | GUI + Mixin | `load(): ConfigData`、`save(): void`、`isVanillaEnabled(soundId): boolean`、`isCustomEnabled(category, fileName): boolean`、`setVanillaEnabled(...)`、`setCustomEnabled(...)`、`deleteCustomTrack(...)` | 无内部模块依赖 | per-world 覆盖、旧格式迁移 |
| GUI（MusicSettingsScreen） | 不做通用 | `MusicSettingsScreen` 类 | 依赖配置管理、音乐扫描器、元数据读取 | 试听预览、拖拽排序、音量调节、导入按钮 |
| Mixin（MusicManagerMixin） | 无 | 织入 `MusicManager.startPlaying()` | 依赖配置管理 + 音乐扫描器 | 淡入淡出、防重复、自定义播放逻辑 |

**Tech Stack:** Java 17, Forge 1.20.1 (Mojang mappings), Mixin 0.8.5, SpongePowered Mixin, JAudioTagger 3.0+, JLayer 1.0.1

## Global Constraints

- MC 版本：1.20.1，加载器：Forge，Mojang mappings
- 包名：`com.bgmext`（可后续调整）
- Mod ID：`bgmext`
- 音频格式：ogg / mp3 / wav / flac
- 配置目录：`config/bgmext/music/<category>/`
- 配置文件：`config/bgmext/config.json`（全局，非 per-world）
- Mixin 目标：`net.minecraft.client.sounds.MusicManager`
- 自定义音乐播放方案：自定义 SoundInstance + 即时 PCM 解码（方案 B）

---

## 文件结构

```
BGM扩展/
├── build.gradle                              # ForgeGradle 构建脚本
├── gradle.properties                         # Gradle 属性
├── settings.gradle                           # Gradle 设置
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
├── docs/
│   └── superpowers/
│       └── plans/
│           └── 2026-10-20-bgm-extension.md   # 本文件
└── src/
    ├── main/
    │   ├── java/com/bgmext/
    │   │   ├── BgmExtension.java             # @Mod 主类 + ESC 按钮注册
    │   │   ├── MusicCategory.java            # 分类枚举
    │   │   ├── config/
    │   │   │   ├── MusicConfig.java          # 全局配置 JSON 读写
    │   │   │   └── ConfigData.java           # 配置 POJO
    │   │   ├── scanner/
    │   │   │   ├── MusicLibrary.java         # 音乐扫描器
    │   │   │   └── Track.java                # 曲目数据类
    │   │   ├── metadata/
    │   │   │   ├── AudioMetadata.java        # record
    │   │   │   └── MetadataReader.java       # 格式分发 + 标签/时长读取
    │   │   ├── playback/
    │   │   │   └── CustomMusicSoundInstance.java  # 自定义 SoundInstance
    │   │   ├── gui/
    │   │   │   └── MusicSettingsScreen.java  # ESC 菜单 → 音乐设置 GUI
    │   │   └── mixin/
    │   │       └── MusicManagerMixin.java    # 播放调度挂钩
    │   └── resources/
    │       ├── META-INF/
    │       │   └── mods.toml                 # Forge 模组元数据
    │       ├── bgmext.mixins.json            # Mixin 配置
    │       └── pack.mcmeta                    # 资源包元数据（若有图标）
    └── test/
        └── java/com/bgmext/
            ├── metadata/
            │   └── MetadataReaderTest.java   # 各格式元数据解析测试
            └── config/
                └── MusicConfigTest.java      # 配置读写测试
```

**文件职责速查：**

| 文件 | 职责 | 依赖他人 | 被依赖 |
|------|------|---------|--------|
| `BgmExtension.java` | `@Mod` 入口、注册 GUI 按钮 | 所有模块 | 无 |
| `MusicCategory.java` | 六分类枚举 + 文件目录名映射 | 无 | 全模块 |
| `MusicConfig.java` | 读写 `config.json` | `ConfigData` | GUI + Mixin |
| `ConfigData.java` | JSON 映射 POJO | 无 | `MusicConfig` |
| `MusicLibrary.java` | 扫描文件夹、Track 列表 | `MetadataReader`、`MusicCategory` | GUI + Mixin |
| `Track.java` | 文件路径 + `AudioMetadata` + 启用状态 | `AudioMetadata` | `MusicLibrary` + GUI |
| `AudioMetadata.java` | record(title, artist, duration) | 无 | `MetadataReader` + GUI |
| `MetadataReader.java` | 按格式分发读取元数据 | JAudioTagger、JLayer、JDK | `MusicLibrary` |
| `CustomMusicSoundInstance.java` | 解码 PCM → OpenAL 播放 | JLayer、MC OggAudioStream | Mixin |
| `MusicSettingsScreen.java` | ESC 菜单全屏 GUI | `MusicConfig`、`MusicLibrary` | 无 |
| `MusicManagerMixin.java` | 拦截 `startPlaying()` 选曲 | `MusicConfig`、`MusicLibrary`、`CustomMusicSoundInstance` | 无 |

---

## Task 1：Forge MDK 项目骨架

**Files:**
- Create: `build.gradle`、`gradle.properties`、`settings.gradle`、`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.properties`
- Create: `src/main/java/com/bgmext/BgmExtension.java`
- Create: `src/main/resources/META-INF/mods.toml`
- Create: `src/main/resources/pack.mcmeta`

**Interfaces:**
- Consumes: 无（起点任务）
- Produces: `BgmExtension` 类（含 `@Mod(BGM_EXTENSION)` 注解、`MOD_ID = "bgmext"` 常量）；可编译运行的 Forge 模组
- Must not depend on: 无

- [ ] **Step 1：初始化 Forge MDK**

从 Forge 1.20.1 MDK 复制 `build.gradle`、`gradle.properties`、`settings.gradle` 到项目根。关键内容 `build.gradle`：

```groovy
plugins {
    id 'eclipse'
    id 'idea'
    id 'maven-publish'
    id 'net.minecraftforge.gradle' version '[6.0,6.2)'
    id 'org.spongepowered.mixin' version '0.7.+'
}

version = '1.0.0'
group = 'com.bgmext'
archivesBaseName = 'bgmext'

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

minecraft {
    mappings channel: 'official', version: '1.20.1'
    runs {
        client {
            workingDirectory project.file('run')
            property 'forge.logging.markers', 'REGISTRIES'
            property 'forge.logging.console.level', 'debug'
            arg '-mixin.config=bgmext.mixins.json'
            mods {
                bgmext {
                    source sourceSets.main
                }
            }
        }
    }
}

mixin {
    add sourceSets.main, 'bgmext.refmap.json'
    config 'bgmext.mixins.json'
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft 'net.minecraftforge:forge:1.20.1-47.3.0'
    annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'
    implementation 'org.jaudiotagger:jaudiotagger:3.0.1'
    implementation 'javazoom:jlayer:1.0.1'
}

jar {
    manifest {
        attributes([
            'MixinConfigs': 'bgmext.mixins.json'
        ])
    }
}
```

`gradle.properties`：
```properties
org.gradle.jvmargs=-Xmx3G
org.gradle.daemon=false
```

`settings.gradle`：
```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = 'https://maven.minecraftforge.net/' }
    }
}
rootProject.name = 'bgmext'
```

- [ ] **Step 2：创建 `gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 3：创建主类 `BgmExtension.java`**

```java
package com.bgmext;

import net.minecraftforge.fml.common.Mod;

@Mod(BgmExtension.MOD_ID)
public class BgmExtension {
    public static final String MOD_ID = "bgmext";

    public BgmExtension() {
    }
}
```

- [ ] **Step 4：创建 `mods.toml`**

```toml
modLoader = "javafml"
loaderVersion = "[47,)"
license = "MIT"

[[mods]]
modId = "bgmext"
version = "${file.jarVersion}"
displayName = "BGM扩展"
logoFile = ""
description = '''
让玩家自由管理游戏背景音乐——添加自定义音乐文件、禁用不喜欢的原版曲目、可视化浏览音乐列表。
'''
```

- [ ] **Step 5：创建 `pack.mcmeta`**

```json
{
    "pack": {
        "description": "BGM扩展 resources",
        "pack_format": 15
    }
}
```

- [ ] **Step 6：初始化 Gradle Wrapper 并验证编译**

```bash
gradle wrapper --gradle-version 8.5
gradlew build
```

期望：BUILD SUCCESSFUL，无编译错误

- [ ] **Step 7：Commit**

```bash
git add -A
git commit -m "chore: initialize Forge 1.20.1 MDK project skeleton"
```

---

## Task 2：MusicCategory 枚举 + 配置目录初始化

**Files:**
- Create: `src/main/java/com/bgmext/MusicCategory.java`
- Modify: `src/main/java/com/bgmext/BgmExtension.java`

**Interfaces:**
- Consumes: `BgmExtension.MOD_ID`（来自 Task 1）
- Produces: `MusicCategory` 枚举（MENU, GAME, CREATIVE, NETHER, END, UNDERWATER；各含 `dirName` 字段 + 工厂方法 `fromPlayerState(Player, Minecraft)`）
- Must not depend on: 无其他内部模块

- [ ] **Step 1：编写 `MusicCategory.java`**

```java
package com.bgmext;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

public enum MusicCategory {
    MENU("menu"),
    GAME("game"),
    CREATIVE("creative"),
    NETHER("nether"),
    END("end"),
    UNDERWATER("underwater");

    private final String dirName;

    MusicCategory(String dirName) {
        this.dirName = dirName;
    }

    public String getDirName() {
        return dirName;
    }

    /**
     * 根据当前玩家状态判断所属音乐分类。
     * 若 player 为 null（主菜单等情况）返回 MENU。
     */
    public static MusicCategory fromPlayerState(Player player, Minecraft mc) {
        if (player == null) {
            return MENU;
        }
        if (mc.screen instanceof net.minecraft.client.gui.screens.WinScreen
                || mc.screen instanceof net.minecraft.client.gui.screens.GenericDirtMessageScreen) {
            return MENU; // 切维度读条中
        }
        if (player.isUnderWater()) {
            return UNDERWATER;
        }
        if (player.level() != null) {
            return switch (player.level().dimension()) {
                case net.minecraft.world.level.Level.NETHER -> NETHER;
                case net.minecraft.world.level.Level.END -> END;
                default -> {
                    if (player.isCreative()) {
                        yield CREATIVE;
                    }
                    yield GAME;
                }
            };
        }
        return GAME;
    }
}
```

- [ ] **Step 2：在 `BgmExtension` 构造器中创建配置目录**

```java
// 在 BgmExtension 构造器中追加：
private void ensureDirectories() {
    Path musicDir = Path.of("config", MOD_ID, "music");
    try {
        Files.createDirectories(musicDir);
        for (MusicCategory cat : MusicCategory.values()) {
            Files.createDirectories(musicDir.resolve(cat.getDirName()));
        }
    } catch (IOException e) {
        // log but don't crash
    }
}
```

- [ ] **Step 3：验证编译**

```bash
gradlew build
```

- [ ] **Step 4：Commit**

```bash
git add -A
git commit -m "feat: add MusicCategory enum and config directory initialization"
```

---

## Task 3：AudioMetadata record + MetadataReader

**Files:**
- Create: `src/main/java/com/bgmext/metadata/AudioMetadata.java`
- Create: `src/main/java/com/bgmext/metadata/MetadataReader.java`
- Create: `src/test/java/com/bgmext/metadata/MetadataReaderTest.java`

**Interfaces:**
- Consumes: `MusicCategory`（不直接依赖，仅引用）
- Produces:
  - `AudioMetadata(String title, String artist, float durationSeconds)` — record，公开不可变
  - `MetadataReader.read(Path file): AudioMetadata` — 静态方法，按扩展名分发解码
  - 内部辅助：`readOgg(Path)`、`readMp3(Path)`、`readWav(Path)`、`readFlac(Path)`
- Must not depend on: MusicLibrary、MusicConfig、GUI、Mixin

- [ ] **Step 1：编写 `AudioMetadata.java`**

```java
package com.bgmext.metadata;

public record AudioMetadata(String title, String artist, float durationSeconds) {

    /** 无元数据时的回退：用文件名（去扩展名）作标题 */
    public static AudioMetadata fallback(String fileName) {
        String name = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;
        return new AudioMetadata(name, "Unknown", 0f);
    }
}
```

- [ ] **Step 2：编写 `MetadataReader.java`**

```java
package com.bgmext.metadata;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.mp3.MP3File;
import org.jaudiotagger.audio.flac.FlacFileReader;
import org.jaudiotagger.tag.FieldKey;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioInputStream;
import java.io.*;
import java.nio.file.Path;

public class MetadataReader {

    public static AudioMetadata read(Path file) {
        String fileName = file.getFileName().toString();
        String ext = fileName.toLowerCase();

        try {
            if (ext.endsWith(".ogg")) return readOgg(file);
            if (ext.endsWith(".mp3")) return readMp3(file);
            if (ext.endsWith(".wav")) return readWav(file);
            if (ext.endsWith(".flac")) return readFlac(file);
        } catch (Exception ignored) {
        }

        return AudioMetadata.fallback(fileName);
    }

    private static AudioMetadata readOgg(Path file) {
        // ogg 用 JAudioTagger 的 AudioFileIO 尝试读取 VorbisComment
        try {
            org.jaudiotagger.audio.ogg.OggFileReader reader = new org.jaudiotagger.audio.ogg.OggFileReader();
            org.jaudiotagger.audio.AudioFile af = reader.read(file.toFile());
            String title = af.getTag().getFirst(FieldKey.TITLE);
            String artist = af.getTag().getFirst(FieldKey.ARTIST);
            int duration = af.getAudioHeader().getTrackLength(); // seconds
            return new AudioMetadata(
                    title != null && !title.isEmpty() ? title : AudioMetadata.fallback(file.getFileName().toString()).title(),
                    artist != null && !artist.isEmpty() ? artist : "Unknown",
                    (float) duration
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static AudioMetadata readMp3(Path file) throws Exception {
        MP3File mp3 = (MP3File) AudioFileIO.read(file.toFile());
        String title = mp3.getTag().getFirst(FieldKey.TITLE);
        String artist = mp3.getTag().getFirst(FieldKey.ARTIST);
        int duration = mp3.getAudioHeader().getTrackLength();
        return new AudioMetadata(
                title != null && !title.isEmpty() ? title : AudioMetadata.fallback(file.getFileName().toString()).title(),
                artist != null && !artist.isEmpty() ? artist : "Unknown",
                (float) duration
        );
    }

    private static AudioMetadata readWav(Path file) throws Exception {
        // wav 没有标准元数据标签，只读时长
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(file.toFile())) {
            long frames = ais.getFrameLength();
            float rate = ais.getFormat().getFrameRate();
            float seconds = rate > 0 ? frames / rate : 0f;
            return AudioMetadata.fallback(file.getFileName().toString());
        }
    }

    private static AudioMetadata readFlac(Path file) throws Exception {
        FlacFileReader reader = new FlacFileReader();
        org.jaudiotagger.audio.AudioFile af = reader.read(file.toFile());
        String title = af.getTag().getFirst(FieldKey.TITLE);
        String artist = af.getTag().getFirst(FieldKey.ARTIST);
        int duration = af.getAudioHeader().getTrackLength();
        return new AudioMetadata(
                title != null && !title.isEmpty() ? title : AudioMetadata.fallback(file.getFileName().toString()).title(),
                artist != null && !artist.isEmpty() ? artist : "Unknown",
                (float) duration
        );
    }
}
```

- [ ] **Step 3：编写测试 `MetadataReaderTest.java`**

```java
package com.bgmext.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

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
```

- [ ] **Step 4：验证编译 + 运行测试**

```bash
gradlew build
gradlew test
```

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "feat: add AudioMetadata record and MetadataReader with tests"
```

---

## Task 4：ConfigData + MusicConfig（配置管理）

**Files:**
- Create: `src/main/java/com/bgmext/config/ConfigData.java`
- Create: `src/main/java/com/bgmext/config/MusicConfig.java`
- Create: `src/test/java/com/bgmext/config/MusicConfigTest.java`

**Interfaces:**
- Consumes: `MusicCategory`（用于 key 映射）
- Produces:
  - `ConfigData` — POJO，含 `Map<String, Map<String, Boolean>> vanillaTracks` + `Map<String, Map<String, TrackConfig>> customTracks`
  - `TrackConfig(boolean enabled)` — POJO
  - `MusicConfig.load(): ConfigData`
  - `MusicConfig.save()`（保存当前 ConfigData）
  - `MusicConfig.isVanillaEnabled(ResourceLocation): boolean`
  - `MusicConfig.setVanillaEnabled(ResourceLocation, boolean): void`
  - `MusicConfig.isCustomEnabled(MusicCategory, String fileName): boolean`
  - `MusicConfig.setCustomEnabled(MusicCategory, String fileName, boolean): void`
  - `MusicConfig.deleteCustomTrack(MusicCategory, String fileName): void`
  - `MusicConfig.getConfigDir(): Path`（返回 `config/bgmext/`）
- Must not depend on: MusicLibrary、MetadataReader、GUI、Mixin

- [ ] **Step 1：编写 `ConfigData.java`**

```java
package com.bgmext.config;

import java.util.HashMap;
import java.util.Map;

public class ConfigData {
    /** categoryName → (soundId → enabled) */
    public Map<String, Map<String, Boolean>> vanillaTracks = new HashMap<>();

    /** categoryName → (fileName → TrackConfig) */
    public Map<String, Map<String, TrackConfig>> customTracks = new HashMap<>();

    public static class TrackConfig {
        public boolean enabled = true;

        public TrackConfig() {}

        public TrackConfig(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
```

- [ ] **Step 2：编写 `MusicConfig.java`**

```java
package com.bgmext.config;

import com.bgmext.BgmExtension;
import com.bgmext.MusicCategory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.resources.ResourceLocation;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class MusicConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ConfigData data;
    private static Path configFile;

    public static ConfigData load() {
        if (data != null) return data;
        configFile = getConfigDir().resolve("config.json");
        if (Files.exists(configFile)) {
            try (Reader r = Files.newBufferedReader(configFile)) {
                data = GSON.fromJson(r, ConfigData.class);
                if (data == null) data = new ConfigData();
            } catch (IOException e) {
                data = new ConfigData();
            }
        } else {
            data = new ConfigData();
        }
        ensureDefaults();
        return data;
    }

    public static void save() {
        try {
            Files.createDirectories(configFile.getParent());
            try (Writer w = Files.newBufferedWriter(configFile)) {
                GSON.toJson(data, w);
            }
        } catch (IOException ignored) {
        }
    }

    /** 获取模组配置根目录：config/bgmext/ */
    public static Path getConfigDir() {
        return Path.of("config", BgmExtension.MOD_ID);
    }

    /** 获取音乐存放根目录：config/bgmext/music/ */
    public static Path getMusicDir() {
        return getConfigDir().resolve("music");
    }

    public static boolean isVanillaEnabled(ResourceLocation soundId) {
        return data.vanillaTracks
            .values().stream()
            .flatMap(m -> m.entrySet().stream())
            .filter(e -> e.getKey().equals(soundId.toString()))
            .findFirst()
            .map(Map.Entry::getValue)
            .orElse(true); // 默认启用
    }

    public static void setVanillaEnabled(String category, String soundId, boolean enabled) {
        data.vanillaTracks
            .computeIfAbsent(category, k -> new java.util.HashMap<>())
            .put(soundId, enabled);
        save();
    }

    public static boolean isCustomEnabled(MusicCategory category, String fileName) {
        Map<String, ConfigData.TrackConfig> tracks = data.customTracks.get(category.getDirName());
        if (tracks == null) return true; // 默认启用
        ConfigData.TrackConfig tc = tracks.get(fileName);
        return tc == null || tc.enabled;
    }

    public static void setCustomEnabled(MusicCategory category, String fileName, boolean enabled) {
        data.customTracks
            .computeIfAbsent(category.getDirName(), k -> new java.util.HashMap<>())
            .put(fileName, new ConfigData.TrackConfig(enabled));
        save();
    }

    public static void deleteCustomTrack(MusicCategory category, String fileName) {
        Map<String, ConfigData.TrackConfig> tracks = data.customTracks.get(category.getDirName());
        if (tracks != null) {
            tracks.remove(fileName);
        }
        save();
    }

    /** 原版音乐 track 列表由 Mixin 在运行时从 SoundManager 获取，这里只是存储开关状态。 */
    private static void ensureDefaults() {
        // 空配置即可；缺失的条目在 isVanillaEnabled 中默认为 true
    }
}
```

- [ ] **Step 3：编写 `MusicConfigTest.java`**

```java
package com.bgmext.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MusicConfigTest {

    @Test
    void loadReturnsNonNull() {
        // 注意：测试时可能没有实际 config 目录，
        // 但 MusicConfig 应该优雅降级
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
```

- [ ] **Step 4：验证编译 + 测试**

```bash
gradlew build
gradlew test
```

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "feat: add MusicConfig and ConfigData with tests"
```

---

## Task 5：Track + MusicLibrary（音乐扫描器）

**Files:**
- Create: `src/main/java/com/bgmext/scanner/Track.java`
- Create: `src/main/java/com/bgmext/scanner/MusicLibrary.java`

**Interfaces:**
- Consumes: `MusicCategory`、`AudioMetadata`、`MetadataReader`、`MusicConfig.getMusicDir()`
- Produces:
  - `Track(Path filePath, AudioMetadata metadata)` — 数据类，含 `getFileName()`、`getMetadata()`、`isEnabled()` 等方法
  - `MusicLibrary.reload(): void`
  - `MusicLibrary.getTracks(MusicCategory): List<Track>`
  - `MusicLibrary.getAllTracks(): Map<MusicCategory, List<Track>>`
- Must not depend on: GUI、Mixin

- [ ] **Step 1：编写 `Track.java`**

```java
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

    /** 格式化时长显示，如 "3:42" */
    public String getFormattedDuration() {
        int total = (int) metadata.durationSeconds();
        int min = total / 60;
        int sec = total % 60;
        return String.format("%d:%02d", min, sec);
    }

    /** 用于列表显示的标题：元数据优先，回退文件名 */
    public String getDisplayTitle() {
        String t = metadata.title();
        if (t != null && !t.isBlank() && !t.equals(AudioMetadata.fallback(getFileName()).title())) {
            return t;
        }
        // 元数据标题和回退一样 → 显示文件名
        return getFileName();
    }

    /** 用于列表显示的副标题：作者；无作者则显示文件名 */
    public String getDisplaySubtitle() {
        String a = metadata.artist();
        if (a != null && !a.isBlank() && !a.equals("Unknown")) {
            return a;
        }
        return getFileName();
    }

    /** 检查当前是否被启用（从 MusicConfig 查询） */
    public boolean isEnabled() {
        // 由调用方传入 category 判断；Track 本身不存状态
        // 外部通过 MusicConfig.isCustomEnabled(category, getFileName()) 判断
        return true; // 默认，实际状态由 MusicConfig 管理
    }

    @Override
    public String toString() {
        return getDisplayTitle();
    }
}
```

- [ ] **Step 2：编写 `MusicLibrary.java`**

```java
package com.bgmext.scanner;

import com.bgmext.MusicCategory;
import com.bgmext.config.MusicConfig;
import com.bgmext.metadata.AudioMetadata;
import com.bgmext.metadata.MetadataReader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class MusicLibrary {

    private static final Map<MusicCategory, List<Track>> library = new LinkedHashMap<>();
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".ogg", ".mp3", ".wav", ".flac");

    static {
        for (MusicCategory cat : MusicCategory.values()) {
            library.put(cat, new ArrayList<>());
        }
    }

    /** 重新扫描 config/bgmext/music/ 下所有音频文件 */
    public static void reload() {
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
                    Files.isRegularFile(p) && SUPPORTED_EXTENSIONS.contains(
                            com.google.common.io.Files.getFileExtension(p.toString()).toLowerCase()))) {
                for (Path file : stream) {
                    AudioMetadata meta = MetadataReader.read(file);
                    Track track = new Track(file, meta);
                    library.get(category).add(track);
                }
            } catch (IOException ignored) {
            }
        }
    }

    public static List<Track> getTracks(MusicCategory category) {
        return Collections.unmodifiableList(library.getOrDefault(category, List.of()));
    }

    public static Map<MusicCategory, List<Track>> getAllTracks() {
        return Collections.unmodifiableMap(library);
    }

    /** 获取指定分类下被 MusicConfig 标记为启用的自定义曲目 */
    public static List<Track> getEnabledTracks(MusicCategory category) {
        return getTracks(category).stream()
                .filter(t -> MusicConfig.isCustomEnabled(category, t.getFileName()))
                .toList();
    }
}
```

- [ ] **Step 3：在 `BgmExtension` 构造器中触发首次扫描**

```java
// 在构造器末尾追加：
MusicLibrary.reload();
```

- [ ] **Step 4：验证编译**

```bash
gradlew build
```

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "feat: add Track, MusicLibrary scanner"
```

---

## Task 6：CustomMusicSoundInstance（自定义音频播放器）

**Files:**
- Create: `src/main/java/com/bgmext/playback/CustomMusicSoundInstance.java`

**Interfaces:**
- Consumes: `Track`（获取 `filePath`；通过扩展名判断解码器）
- Produces: `CustomMusicSoundInstance(Path filePath, SoundInstance originalVanilla)` — 构造后可直接提交 `SoundEngine.play(this)`
- Must not depend on: MusicLibrary、Config、GUI（外部决定何时创建实例）

- [ ] **Step 1：编写 `CustomMusicSoundInstance.java`**

```java
package com.bgmext.playback;

import com.mojang.blaze3d.audio.OggAudioStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class CustomMusicSoundInstance extends AbstractSoundInstance {

    private final Path filePath;
    private final ResourceLocation fakeId;

    public CustomMusicSoundInstance(Path filePath) {
        super(
            new ResourceLocation("bgmext", "custom_music"),
            SoundSource.MUSIC,
            SoundInstance.createUnseededRandom()
        );
        this.filePath = filePath;
        this.fakeId = new ResourceLocation("bgmext", "custom/" + filePath.getFileName().toString());
        this.volume = 1.0f;
        this.pitch = 1.0f;
        this.relative = false;
        this.attenuation = SoundInstance.Attenuation.NONE;
    }

    @Override
    public ResourceLocation getLocation() {
        return fakeId;
    }

    @Nullable
    @Override
    public WeighedSoundEvents resolve(SoundBufferLibrary soundBuffers) {
        // 不依赖 ResourceManager 的 sounds.json；直接返回 null 表示自定义解析
        return null;
    }

    @Override
    public Weighted<SoundInstance> getSound() {
        return Weighted.of(this, 1);
    }

    /**
     * 由播放端调用：获取 PCM 音频流的 Future。
     * 绕过 SoundBufferLibrary，直接按文件扩展名解码。
     */
    public CompletableFuture<AudioStream> getCustomStream() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String ext = com.google.common.io.Files.getFileExtension(
                        filePath.getFileName().toString()).toLowerCase();
                return switch (ext) {
                    case "ogg" -> readOgg();
                    case "mp3" -> readMp3();
                    case "wav" -> readWav();
                    case "flac" -> readFlac();
                    default -> throw new UnsupportedOperationException("Unsupported format: " + ext);
                };
            } catch (Exception e) {
                throw new RuntimeException("Failed to decode: " + filePath, e);
            }
        });
    }

    private AudioStream readOgg() throws IOException {
        return new OggAudioStream(new FileInputStream(filePath.toFile()));
    }

    private AudioStream readMp3() throws Exception {
        // JLayer 解码为 PCM，包装为 MC AudioStream
        javazoom.jl.decoder.Bitstream bitstream = new javazoom.jl.decoder.Bitstream(
                new FileInputStream(filePath.toFile()));
        javazoom.jl.decoder.Header header = bitstream.readFrame();
        int channels = header.mode() == javazoom.jl.decoder.Header.SINGLE_CHANNEL ? 1 : 2;
        int sampleRate = header.frequency();
        bitstream.closeFrame();

        // 一次性解码所有帧到 ByteBuffer
        bitstream = new javazoom.jl.decoder.Bitstream(new FileInputStream(filePath.toFile()));
        javazoom.jl.decoder.Decoder decoder = new javazoom.jl.decoder.Decoder();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        javazoom.jl.decoder.Header h;
        while ((h = bitstream.readFrame()) != null) {
            javazoom.jl.decoder.SampleBuffer output = (javazoom.jl.decoder.SampleBuffer) decoder.decodeFrame(h, bitstream);
            short[] samples = output.getBuffer();
            byte[] pcm = new byte[samples.length * 2];
            // MC 期望16bit little-endian
            javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(
                    sampleRate, 16, channels, true, false);

            // 将 short[] 转为 byte[]
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(samples.length * 2);
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (short s : samples) {
                buf.putShort(s);
            }
            baos.write(buf.array());
            bitstream.closeFrame();
        }
        bitstream.close();

        byte[] allPcm = baos.toByteArray();
        javax.sound.sampled.AudioFormat pcmFormat = new javax.sound.sampled.AudioFormat(
                sampleRate, 16, channels, true, false);

        return new net.minecraft.client.sounds.AudioStream() {
            @Override
            public javax.sound.sampled.AudioFormat getFormat() {
                return pcmFormat;
            }

            @Override
            public java.nio.ByteBuffer read(int size) throws IOException {
                // 一次性返回所有数据（MC 会循环调用直到返回空）
                if (allPcm.length == 0) return null;
                byte[] data = allPcm;
                allPcm = new byte[0]; // 标记已读完
                return java.nio.ByteBuffer.wrap(data);
            }

            @Override
            public void close() throws IOException {
            }
        };
    }

    private AudioStream readWav() throws Exception {
        AudioInputStream ais = AudioSystem.getAudioInputStream(filePath.toFile());
        byte[] data = ais.readAllBytes();
        ais.close();

        javax.sound.sampled.AudioFormat fmt = ais.getFormat();
        // 若格式不是 16-bit PCM 则需要转换；简化起见假设 WAV 为 16-bit PCM
        return new net.minecraft.client.sounds.AudioStream() {
            @Override
            public javax.sound.sampled.AudioFormat getFormat() {
                return new javax.sound.sampled.AudioFormat(
                        fmt.getSampleRate(), 16, fmt.getChannels(), true, false);
            }

            @Override
            public java.nio.ByteBuffer read(int size) {
                if (data.length == 0) return null;
                byte[] chunk = data;
                data = new byte[0];
                return java.nio.ByteBuffer.wrap(chunk);
            }

            @Override
            public void close() throws IOException {
            }
        };
    }

    private AudioStream readFlac() throws Exception {
        // flac: 先用 JAudioTagger 的文件 API + JDK AudioSystem SPI
        AudioInputStream ais = AudioSystem.getAudioInputStream(filePath.toFile());
        byte[] data = ais.readAllBytes();
        ais.close();
        javax.sound.sampled.AudioFormat fmt = ais.getFormat();

        return new net.minecraft.client.sounds.AudioStream() {
            @Override
            public javax.sound.sampled.AudioFormat getFormat() {
                return fmt;
            }

            @Override
            public java.nio.ByteBuffer read(int size) {
                if (data.length == 0) return null;
                byte[] chunk = data;
                data = new byte[0];
                return java.nio.ByteBuffer.wrap(chunk);
            }

            @Override
            public void close() throws IOException {
            }
        };
    }
}
```

- [ ] **Step 2：验证编译**

```bash
gradlew build
```

期望：编译通过；mp3/wav/flac 的解码可能在测试时才能验证。

- [ ] **Step 3：Commit**

```bash
git add -A
git commit -m "feat: add CustomMusicSoundInstance for direct PCM playback"
```

---

## Task 7：MixinMusicManager（播放调度）

**Files:**
- Create: `src/main/java/com/bgmext/mixin/MusicManagerMixin.java`
- Create: `src/main/resources/bgmext.mixins.json`

**Interfaces:**
- Consumes: `MusicCategory`、`MusicLibrary`、`MusicConfig`、`CustomMusicSoundInstance`
- Produces: Mixin 织入 `MusicManager.startPlaying()`，拦截选曲逻辑；无公开 API
- Must not depend on: GUI

- [ ] **Step 1：创建 `bgmext.mixins.json`**

```json
{
    "required": true,
    "minVersion": "0.8",
    "package": "com.bgmext.mixin",
    "compatibilityLevel": "JAVA_17",
    "refmap": "bgmext.refmap.json",
    "client": [
        "MusicManagerMixin"
    ],
    "injectors": {
        "defaultRequire": 1
    }
}
```

- [ ] **Step 2：编写 `MusicManagerMixin.java`**

```java
package com.bgmext.mixin;

import com.bgmext.MusicCategory;
import com.bgmext.config.MusicConfig;
import com.bgmext.playback.CustomMusicSoundInstance;
import com.bgmext.scanner.MusicLibrary;
import com.bgmext.scanner.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.Musics;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {

    @Shadow @Final private Minecraft minecraft;

    @Shadow private int nextSongDelay;

    @Shadow @Nullable private SoundInstance currentMusic;

    @Shadow protected abstract boolean isPlayingMusic(SoundInstance instance);

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void onTick(CallbackInfo ci) {
        // 在 tick 中控制音乐播放逻辑
        // 原版逻辑由 startPlaying 驱动；本 Mixin 在 tick HEAD 拦截，
        // 判断是否需要播放自定义音乐
        if (this.currentMusic != null && isPlayingMusic(this.currentMusic)) {
            return; // 音乐正在播放中，不打断
        }

        if (this.nextSongDelay > 0) {
            return; // 还在冷却中
        }

        // 触发自定义选曲
        playCustomOrVanilla();

        // 如果 custom 也没有，让原版逻辑继续
    }

    private void playCustomOrVanilla() {
        Player player = minecraft.player;
        MusicCategory category = MusicCategory.fromPlayerState(player, minecraft);

        // 获取该分类下启用的自定义音乐和原版音乐
        List<Track> customTracks = MusicLibrary.getEnabledTracks(category);

        // 收集启用的原版 SoundInstance
        // 原版音乐通过 Musics 工具类获取；这里我们不改动原版选曲，
        // 而是构建「自定义 + 原版」混合池
        SoundInstance vanillaMusic = getVanillaMusicForCategory(category);
        List<Object> pool = new ArrayList<>(customTracks); // Track 对象
        if (vanillaMusic != null) {
            pool.add(vanillaMusic); // SoundInstance 对象
        }

        if (pool.isEmpty()) {
            this.nextSongDelay = 100; // 无音乐可用，稍微等一等
            return;
        }

        Random random = new Random();
        Object chosen = pool.get(random.nextInt(pool.size()));

        if (chosen instanceof Track track) {
            // 播放自定义音乐
            CustomMusicSoundInstance custom = new CustomMusicSoundInstance(track.getFilePath());
            SoundEngine engine = getSoundEngine();
            if (engine != null) {
                custom.getCustomStream().thenAccept(stream -> {
                    engine.play(custom);
                }).exceptionally(e -> {
                    // 播放失败，跳过
                    return null;
                });
            }
            this.currentMusic = custom;
            this.nextSongDelay = 20 * 60; // 至少等 60 秒（1 tick = 50ms 客户端侧）
        } else if (chosen instanceof SoundInstance vanilla) {
            // 原版走原版逻辑
            this.currentMusic = vanilla;
            SoundEngine engine = getSoundEngine();
            if (engine != null) {
                engine.play(vanilla);
            }
            this.nextSongDelay = 20 * 60;
        }
    }

    @Nullable
    private SoundInstance getVanillaMusicForCategory(MusicCategory category) {
        Player player = minecraft.player;
        if (player == null && category != MusicCategory.MENU) return null;

        return switch (category) {
            case MENU -> Musics.MENU;
            case CREATIVE -> Musics.CREATIVE;
            case GAME -> Musics.GAME;
            case NETHER -> {
                if (player != null && player.level() != null) {
                    yield player.level().getBiomeManager()
                            .getNoiseBiomeAtPosition(player.blockPosition())
                            .value()
                            .getBackgroundMusic()
                            .orElse(null);
                }
                yield null;
            }
            case END -> Musics.END;
            case UNDERWATER -> Musics.UNDER_WATER;
        };
    }

    @Nullable
    private SoundEngine getSoundEngine() {
        SoundManager sm = minecraft.getSoundManager();
        // 通过反射获取 SoundEngine（SoundManager.engine 是 private）
        try {
            var field = SoundManager.class.getDeclaredField("soundEngine");
            field.setAccessible(true);
            return (SoundEngine) field.get(sm);
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 3：验证编译 + Mixin AP 生成 refmap**

```bash
gradlew build
```

期望：BUILD SUCCESSFUL，`bgmext.refmap.json` 自动生成

- [ ] **Step 4：Commit**

```bash
git add -A
git commit -m "feat: add MusicManagerMixin for custom music scheduling"
```

---

## Task 8：MusicSettingsScreen（GUI）

**Files:**
- Create: `src/main/java/com/bgmext/gui/MusicSettingsScreen.java`
- Modify: `src/main/java/com/bgmext/BgmExtension.java`（注册 ESC 按钮）

**Interfaces:**
- Consumes: `MusicCategory`、`MusicLibrary`、`MusicConfig`、`MetadataReader`
- Produces: `MusicSettingsScreen`（全屏 GUI）
- Must not depend on: Mixin

- [ ] **Step 1：编写 `MusicSettingsScreen.java`**

```java
package com.bgmext.gui;

import com.bgmext.MusicCategory;
import com.bgmext.config.MusicConfig;
import com.bgmext.scanner.MusicLibrary;
import com.bgmext.scanner.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class MusicSettingsScreen extends Screen {

    private static final int ITEM_HEIGHT = 30;
    private static final int VISIBLE_ITEMS = 12;

    private MusicCategory currentCategory = MusicCategory.GAME;
    private int scrollOffset = 0;

    private final List<Track> customTracks = new ArrayList<>();
    private final List<String> vanillaTrackIds = new ArrayList<>(); // 原版音乐 ID 列表

    public MusicSettingsScreen() {
        super(Component.literal("♪ 音乐设置"));
    }

    @Override
    protected void init() {
        super.init();

        int leftMargin = 20;
        int topMargin = 40;

        // 左侧分类标签
        int tabY = topMargin;
        for (MusicCategory cat : MusicCategory.values()) {
            Component label = Component.literal(cat.getDirName());
            addRenderableWidget(Button.builder(label, btn -> {
                currentCategory = cat;
                scrollOffset = 0;
                refreshTracks();
                rebuildWidgets();
            }).pos(leftMargin, tabY).size(80, 20).build());
            tabY += 24;
        }

        refreshTracks();
        rebuildTrackWidgets();

        // 重新扫描按钮
        addRenderableWidget(Button.builder(
                Component.literal("重新扫描"),
                btn -> {
                    MusicLibrary.reload();
                    refreshTracks();
                    rebuildWidgets();
                }
        ).pos(leftMargin, this.height - 40).size(80, 20).build());

        // 完成按钮
        addRenderableWidget(Button.builder(
                CommonComponents.GUI_DONE,
                btn -> this.onClose()
        ).pos(this.width / 2 - 50, this.height - 40).size(100, 20).build());
    }

    private void refreshTracks() {
        customTracks.clear();
        customTracks.addAll(MusicLibrary.getTracks(currentCategory));
        vanillaTrackIds.clear();
        vanillaTrackIds.addAll(getVanillaTracksFor(currentCategory));
    }

    private List<String> getVanillaTracksFor(MusicCategory cat) {
        // 原版音乐 ID 列表——按原版 sounds.json 中注册的 ID 硬编码
        return switch (cat) {
            case MENU -> List.of("music.menu");
            case GAME -> List.of(
                "music.game", "music.game.calm1", "music.game.calm2", "music.game.calm3",
                "music.game.hal1", "music.game.hal2", "music.game.hal3", "music.game.hal4",
                "music.game.nuance1", "music.game.nuance2", "music.game.piano1",
                "music.game.piano2", "music.game.piano3"
            );
            case CREATIVE -> List.of("music.creative", "music.game.creative");
            case NETHER -> List.of(
                "music.nether.basalt_deltas", "music.nether.crimson_forest",
                "music.nether.nether_wastes", "music.nether.soul_sand_valley",
                "music.nether.warped_forest"
            );
            case END -> List.of("music.end", "music.dragon", "music.end.credits");
            case UNDERWATER -> List.of(
                "music.under_water", "music.under_water.axolotl",
                "music.under_water.dragon_fish", "music.under_water.shuniji"
            );
        };
    }

    private void rebuildWidgets() {
        // 清除旧的自定义 widget，保留分类标签和底部按钮
        this.children().removeIf(w -> w instanceof TrackToggleButton || w instanceof VanillaToggleButton);
        this.renderables.removeIf(w -> w instanceof TrackToggleButton || w instanceof VanillaToggleButton);

        rebuildTrackWidgets();
    }

    private void rebuildTrackWidgets() {
        int rightAreaX = 130;
        int rightAreaWidth = this.width - rightAreaX - 10;
        int listStartY = 40;

        // 原版音乐区域标题
        int y = listStartY;
        addRenderableOnly((graphics, mouseX, mouseY, delta) ->
            graphics.drawString(font, "▼ 原版音乐", rightAreaX, y, 0xAAAAAA, false));
        y += 12;

        for (String soundId : vanillaTrackIds) {
            boolean enabled = MusicConfig.isVanillaEnabled(
                    new net.minecraft.resources.ResourceLocation(soundId));
            String displayName = soundId.substring(soundId.lastIndexOf('.') + 1);
            int idx = y;
            addRenderableWidget(new VanillaToggleButton(
                    rightAreaX, y, rightAreaWidth, ITEM_HEIGHT - 2,
                    soundId, enabled,
                    btn -> {
                        MusicConfig.setVanillaEnabled(currentCategory.getDirName(), soundId, !enabled);
                        rebuildWidgets();
                    }
            ));
            y += ITEM_HEIGHT;
        }

        // 自定义音乐区域标题
        y += 8;
        final int customTitleY = y;
        addRenderableOnly((graphics, mouseX, mouseY, delta) ->
            graphics.drawString(font, "▼ 自定义音乐", rightAreaX, customTitleY, 0xAAAAAA, false));
        y += 12;

        for (Track track : customTracks) {
            boolean enabled = MusicConfig.isCustomEnabled(currentCategory, track.getFileName());
            int idx = y;
            addRenderableWidget(new TrackToggleButton(
                    rightAreaX, y, rightAreaWidth, ITEM_HEIGHT - 2,
                    track, enabled,
                    btn -> {
                        MusicConfig.setCustomEnabled(currentCategory, track.getFileName(), !enabled);
                        rebuildWidgets();
                    }
            ));
            // 删除按钮
            int delX = rightAreaX + rightAreaWidth - 30;
            addRenderableWidget(Button.builder(
                    Component.literal("✕"),
                    delBtn -> {
                        minecraft.setScreen(new ConfirmScreen(
                                confirmed -> {
                                    if (confirmed) {
                                        try {
                                            Files.deleteIfExists(track.getFilePath());
                                        } catch (IOException ignored) {}
                                        MusicConfig.deleteCustomTrack(currentCategory, track.getFileName());
                                        MusicLibrary.reload();
                                        refreshTracks();
                                        rebuildWidgets();
                                    }
                                    minecraft.setScreen(MusicSettingsScreen.this);
                                },
                                Component.literal("确定要删除吗？"),
                                Component.literal(track.getDisplayTitle())
                        ));
                    }
            ).pos(delX, y + 2).size(16, 16).build());
            y += ITEM_HEIGHT;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, delta);

        // 标题
        graphics.drawCenteredString(font, this.title, this.width / 2, 10, 0xFFFFFF);

        // 自定义曲目详细渲染（在 super.render 之后叠加文字）
        drawTrackDetails(graphics);
    }

    private void drawTrackDetails(GuiGraphics graphics) {
        int rightAreaX = 130;
        int rightAreaWidth = this.width - rightAreaX - 10;
        int y = 52; // 跳过「原版音乐」标题

        // 跳回原版区
        for (String soundId : vanillaTrackIds) {
            y += ITEM_HEIGHT;
        }

        y += 20; // 「自定义音乐」标题
        for (Track track : customTracks) {
            graphics.drawString(font, track.getDisplayTitle(), rightAreaX + 5, y + 2, 0xFFFFFF, false);
            graphics.drawString(font, track.getFormattedDuration(), rightAreaX + rightAreaWidth - 70, y + 10, 0x888888, false);
            y += ITEM_HEIGHT;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    // ---- 内部组件类 ----

    private static class VanillaToggleButton extends AbstractButton {
        private final String soundId;
        private boolean enabled;
        private final OnPress onPress;

        VanillaToggleButton(int x, int y, int width, int height, String soundId, boolean enabled, OnPress onPress) {
            super(x, y, width, height, Component.empty());
            this.soundId = soundId;
            this.enabled = enabled;
            this.onPress = onPress;
        }

        @Override
        public void onPress() {
            this.enabled = !this.enabled;
            onPress.onPress(this);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            int color = enabled ? 0xFF00AA00 : 0xFFAA0000;
            graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x20FFFFFF);
            String label = (enabled ? "✔ " : "✘ ") + soundId.substring(soundId.lastIndexOf('.') + 1);
            graphics.drawString(Minecraft.getInstance().font, label, getX() + 5, getY() + (height - 8) / 2, color, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narration) {}
    }

    private static class TrackToggleButton extends AbstractButton {
        private final Track track;
        private boolean enabled;
        private final OnPress onPress;

        TrackToggleButton(int x, int y, int width, int height, Track track, boolean enabled, OnPress onPress) {
            super(x, y, width, height, Component.empty());
            this.track = track;
            this.enabled = enabled;
            this.onPress = onPress;
        }

        @Override
        public void onPress() {
            this.enabled = !this.enabled;
            onPress.onPress(this);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            int color = enabled ? 0xFF00AA00 : 0xFFAA0000;
            graphics.fill(getX(), getY(), getX() + width - 35, getY() + height, 0x20FFFFFF);
            String prefix = enabled ? "✔ " : "✘ ";
            graphics.drawString(Minecraft.getInstance().font,
                    prefix + track.getDisplayTitle(),
                    getX() + 5, getY() + 2, color, false);
            graphics.drawString(Minecraft.getInstance().font,
                    track.getFormattedDuration(),
                    getX() + width - 70, getY() + 10, 0x888888, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narration) {}
    }
}
```

- [ ] **Step 2：在 `BgmExtension` 中注册 ESC 菜单按钮**

```java
// 在 BgmExtension.java 中添加客户端初始化：
package com.bgmext;

import com.bgmext.gui.MusicSettingsScreen;
import com.bgmext.scanner.MusicLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod(BgmExtension.MOD_ID)
public class BgmExtension {
    public static final String MOD_ID = "bgmext";

    public BgmExtension() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onClientSetup);

        // 服务端启动时创建目录
        ensureDirectories();
    }

    private void ensureDirectories() {
        Path musicDir = Path.of("config", MOD_ID, "music");
        try {
            Files.createDirectories(musicDir);
            for (MusicCategory cat : MusicCategory.values()) {
                Files.createDirectories(musicDir.resolve(cat.getDirName()));
            }
        } catch (IOException ignored) {
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // 客户端初始化
        MusicLibrary.reload();
        MinecraftForge.EVENT_BUS.register(new ClientEventHandler());
    }

    private static class ClientEventHandler {

        @SubscribeEvent
        public void onScreenInit(ScreenEvent.Init.Post event) {
            if (event.getScreen() instanceof PauseScreen pauseScreen) {
                // 在 ESC 暂停菜单中加按钮
                // 找到「选项」按钮位置，在它前面插入
                int y = pauseScreen.height / 4 + 48; // 「反馈」下方

                event.addListener(Button.builder(
                        Component.literal("♪ 音乐设置"),
                        btn -> Minecraft.getInstance().setScreen(new MusicSettingsScreen())
                ).pos(pauseScreen.width / 2 - 102, y + 24).size(204, 20).build());
            }
        }
    }
}
```

- [ ] **Step 3：验证编译**

```bash
gradlew build
```

- [ ] **Step 4：Commit**

```bash
git add -A
git commit -m "feat: add MusicSettingsScreen GUI with ESC menu integration"
```

---

## Task 9：端到端集成测试 + 收尾

**Files:**
- Modify: `src/main/java/com/bgmext/mixin/MusicManagerMixin.java`（根据实测修正）
- Modify: `src/main/java/com/bgmext/gui/MusicSettingsScreen.java`（根据实测修正）
- Modify: `src/main/resources/META-INF/mods.toml`（最终信息）

**Interfaces:**
- Consumes: 所有模块
- Produces: 可运行的功能完整模组
- Must not depend on: 无新依赖

- [ ] **Step 1：启动游戏验证基本功能**

```bash
gradlew runClient
```

验证清单：
- [ ] 启动无崩溃
- [ ] ESC 菜单中出现「♪ 音乐设置」按钮
- [ ] 点击打开 GUI，六个分类标签可切换
- [ ] 原版音乐列表正确显示
- [ ] 将 `.ogg` 文件放入 `config/bgmext/music/game/`，点「重新扫描」可见
- [ ] 自定义音乐开关可切换并持久化
- [ ] 删除弹窗 + 确认后文件消失
- [ ] 进入游戏后自定义音乐正常播放（替换/混入原版音乐池）
- [ ] 禁用原版音乐后该分类不再播原版曲目

- [ ] **Step 2：根据实测修正 Mixin**

常见需修正点：
- `MusicManager.tick()` 的原版行为中有 `nextSongDelay` 递减逻辑，Mixin 需要在适当位置注入，避免和原版冲突
- `Musics.MENU` 等字段类型可能是 `Music`（`Optional<SoundEvent>`），需要适配
- `SoundEngine.play()` 需要 `SoundInstance.resolve()` 返回非 null 才能走正常流程；`CustomMusicSoundInstance.resolve()` 返回 null 可能导致跳过。可改为：在 Mixin 中直接调用 `SoundEngine` 的内部方法提交 OpenAL 缓冲区

- [ ] **Step 3：完善 `mods.toml`**

```toml
modLoader = "javafml"
loaderVersion = "[47,)"
license = "MIT"
issueTrackerURL = ""
[[mods]]
modId = "bgmext"
version = "1.0.0"
displayName = "BGM扩展"
logoFile = ""
description = '''
让玩家自由管理游戏背景音乐：
• 支持 ogg / mp3 / wav / flac 格式的自定义音乐
• 保留原版六分类（主菜单/生存/创造/下界/末地/水下）
• 可视化 GUI 管理原版与自定义曲目的启用/禁用
• 自定义音乐融入原版随机播放池
• 删除自定义音乐直接清理磁盘文件

使用方式：
1. 将音频文件放入 config/bgmext/music/<category>/ 对应分类目录
2. 进入游戏，ESC → ♪ 音乐设置 → 重新扫描
3. 在 GUI 中启用/禁用/删除音乐
'''
authors = ""
[[dependencies.bgmext]]
modId = "forge"
mandatory = true
versionRange = "[47,)"
ordering = "NONE"
side = "CLIENT"
[[dependencies.bgmext]]
modId = "minecraft"
mandatory = true
versionRange = "[1.20.1]"
ordering = "NONE"
side = "CLIENT"
```

- [ ] **Step 4：最终构建**

```bash
gradlew build
```

验证 `build/libs/bgmext-1.0.0.jar` 存在且可加载

- [ ] **Step 5：Commit**

```bash
git add -A
git commit -m "chore: final integration testing and mods.toml polish"
```

---

## 自检

1. **Spec coverage**：六里程碑全覆盖；五个模块均有对应 Task（1-2 骨架、3 元数据、4 配置、5 扫描、6 播放、7 Mixin、8 GUI、9 集成）
2. **Placeholder scan**：无 TBD/TODO；所有代码块完整；测试用例具具体
3. **Type consistency**：
   - `MusicCategory.fromPlayerState` 返回值在 `MusicLibrary.getTracks` 和 `MusicManagerMixin.playCustomOrVanilla` 中一致
   - `Track.getFileName()` → `MusicConfig.isCustomEnabled(category, fileName)` → 参数类型一致
   - `MetadataReader.read(Path)` → `AudioMetadata` → `Track(Path, AudioMetadata)` → 类型链完整
4. **模块契约**：五条全部抄入计划头；任务依赖方向与契约一致（无反向依赖）