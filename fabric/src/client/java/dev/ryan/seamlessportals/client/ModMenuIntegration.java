package dev.ryan.seamlessportals.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu's entry point (only loaded when Mod Menu is installed): its settings button opens {@link ClientConfigScreen}. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ClientConfigScreen::new;
	}
}
