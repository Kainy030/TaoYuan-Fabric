package com.taoyuan.client;

import net.fabricmc.api.ClientModInitializer;

import com.taoyuan.client.hud.CalamityIndicator;

public class TaoYuanModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CalamityIndicator.register();
	}
}