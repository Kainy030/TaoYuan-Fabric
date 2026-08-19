package com.taoyuan;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.taoyuan.attachment.TaoYuanAttachments;
import com.taoyuan.effect.CalamityTerror;
import com.taoyuan.effect.TaoYuanEffects;
import com.taoyuan.event.FriendlyKillTracker;
import com.taoyuan.item.TaoYuanItemGroups;
import com.taoyuan.item.TaoYuanItems;

public class TaoYuanMod implements ModInitializer {
	public static final String MOD_ID = "taoyuan";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// The tab must exist before items are attached to it.
		TaoYuanItemGroups.initialize();
		TaoYuanItems.initialize();
		TaoYuanAttachments.initialize();
		TaoYuanEffects.initialize();

		// Gameplay subsystems. The terror scheduler and the kill counter both need to be
		// ready before any wave can escalate, so they live with the other init calls.
		CalamityTerror.register();
		FriendlyKillTracker.register();

		LOGGER.info("[TaoYuan]Loaded successfully!");
		LOGGER.info("【桃源】已成功加载！");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
