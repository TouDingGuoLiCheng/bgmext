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
            if (player.level().dimension().equals(net.minecraft.world.level.Level.NETHER)) {
                return NETHER;
            }
            if (player.level().dimension().equals(net.minecraft.world.level.Level.END)) {
                return END;
            }
            if (player.isCreative()) {
                return CREATIVE;
            }
            return GAME;
        }
        return GAME;
    }
}