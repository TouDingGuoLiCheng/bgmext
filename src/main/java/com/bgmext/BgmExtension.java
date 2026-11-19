package com.bgmext;

import com.bgmext.config.MusicConfig;
import com.bgmext.gui.MusicSettingsScreen;
import com.bgmext.scanner.MusicLibrary;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod(BgmExtension.MOD_ID)
public class BgmExtension {
    public static final String MOD_ID = "bgmext";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 兜底入口：暂停菜单按钮被模组包布局挤掉时，用这个键也能打开 */
    public static final KeyMapping OPEN_MUSIC_SETTINGS = new KeyMapping(
            "key.bgmext.open_music_settings",
            GLFW.GLFW_KEY_F9,
            "key.categories.misc");

    public BgmExtension() {
        LOGGER.info("[bgmext] constructor start");
        ensureDirectories();

        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onClientSetup);
        modBus.addListener(this::onRegisterKeyMappings);
    }

    private void ensureDirectories() {
        Path musicDir = Path.of("config", MOD_ID, "music");
        try {
            Files.createDirectories(musicDir);
            for (MusicCategory cat : MusicCategory.values()) {
                Files.createDirectories(musicDir.resolve(cat.getDirName()));
            }
            writeReadmeIfAbsent(musicDir);
            LOGGER.info("[bgmext] music dir ready: {}", musicDir.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("[bgmext] failed to create music dir", e);
        }
    }

    /** 放一份说明在音乐目录里，方便日后忘记文件夹对应哪个场景 */
    private void writeReadmeIfAbsent(Path musicDir) throws IOException {
        Path readme = musicDir.resolve("README.txt");
        if (Files.exists(readme)) return;

        String text = String.join(System.lineSeparator(),
                "BGM扩展 - 自定义背景音乐",
                "================================",
                "",
                "把音频文件放进对应场景的子文件夹即可（支持 .ogg / .mp3 / .wav / .flac）：",
                "",
                "  menu\\        主菜单",
                "  game\\        生存模式",
                "  creative\\    创造模式",
                "  nether\\      下界",
                "  end\\         末地",
                "  underwater\\  水下",
                "",
                "放好之后进游戏：按 ESC -> \"音乐设置\" -> 选择分类 -> 点\"重新扫描\"",
                "如果 ESC 菜单里找不到按钮，可以按 F9 打开（可在 选项->控制 里改键）。",
                "",
                "说明：",
                "- 每首歌都可以单独启用/禁用；关闭的原版曲目不会再被抽到。",
                "- 自定义音乐会与原版音乐混合进同一个随机池。",
                "- 列表里的红色叉号会真实删除磁盘文件，删了就找不回来了。",
                "");
        Files.writeString(readme, text);
        LOGGER.info("[bgmext] wrote README to {}", readme.toAbsolutePath());
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MUSIC_SETTINGS);
        LOGGER.info("[bgmext] keybind registered (F9 by default)");
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // 必须先加载配置，否则各查询接口拿到的是 null
        MusicConfig.load();
        MusicLibrary.reload();

        MinecraftForge.EVENT_BUS.register(new ClientEventHandler());
        LOGGER.info("[bgmext] client setup done, custom tracks = {}",
                MusicLibrary.getAllTracks().values().stream().mapToInt(java.util.List::size).sum());
    }

    private static class ClientEventHandler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            while (OPEN_MUSIC_SETTINGS.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen == null && mc.level != null) {
                    LOGGER.info("[bgmext] opening music settings via keybind");
                    mc.setScreen(new MusicSettingsScreen());
                }
            }
        }

        @SubscribeEvent
        public void onScreenInit(ScreenEvent.Init.Post event) {
            if (!(event.getScreen() instanceof PauseScreen pauseScreen)) {
                return;
            }

            // 找到已有的最底部 widget，把我们的按钮放在它下面，避免任何重叠
            int lowest = 0;
            for (var listener : pauseScreen.children()) {
                if (listener instanceof AbstractWidget w
                        && w.visible
                        && w.getWidth() >= 40
                        && w.getHeight() >= 14) {
                    lowest = Math.max(lowest, w.getY() + w.getHeight());
                }
            }

            int btnW = 204;
            int btnX = pauseScreen.width / 2 - 102;
            int btnY = lowest + 6;
            String mode = "below-lowest";

            // 放不下就退到「返回游戏」右侧的空白处
            if (btnY + 20 > pauseScreen.height - 4) {
                mode = "right-of-top-row";
                btnW = 150;
                btnX = Math.min(pauseScreen.width - btnW - 8, pauseScreen.width / 2 + 110);
                btnY = pauseScreen.height / 4 + 48 - 16;
            }

            LOGGER.info("[bgmext] pause button: mode={} x={} y={} (lowest={}, screen={}x{})",
                    mode, btnX, btnY, lowest, pauseScreen.width, pauseScreen.height);

            event.addListener(Button.builder(
                    Component.literal("\u266a \u97f3\u4e50\u8bbe\u7f6e"),
                    btn -> {
                        LOGGER.info("[bgmext] music settings button clicked");
                        Minecraft.getInstance().setScreen(new MusicSettingsScreen());
                    }
            ).pos(btnX, btnY).size(btnW, 20).build());
        }
    }
}