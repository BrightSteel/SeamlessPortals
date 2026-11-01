package dev.ryan.netherview.client;

import dev.ryan.netherview.compat.ClientVersioned;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/**
 * The client settings ({@link ClientConfig}), opened from Mod Menu. Changes apply as they're made;
 * the file is written when the screen closes.
 */
public final class ClientConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 150;
	private static final int[] CACHE_SIZES_MB = {16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192};

	private final Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

	public ClientConfigScreen(Screen parent) {
		super(Component.translatable("netherview.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ClientConfig config = ClientConfig.get();
		this.layout.addTitleHeader(this.title, this.font);
		GridLayout grid = new GridLayout().columnSpacing(10).rowSpacing(4);
		GridLayout.RowHelper rows = grid.createRowHelper(2);

		rows.addChild(slider("maxLivePortals", 0, 8, 1, () -> config.maxLivePortals, v -> config.maxLivePortals = v, Integer::toString));
		rows.addChild(slider("liveViewDistance", 0, 256, 8, () -> (int) config.liveViewDistance, v -> config.liveViewDistance = v,
				v -> Component.translatable("netherview.config.blocks", v).getString()));
		rows.addChild(slider("remoteRenderDistance", 2, 32, 1, () -> config.remoteRenderDistance, v -> config.remoteRenderDistance = v,
				v -> Component.translatable("netherview.config.chunks", v).getString()));
		rows.addChild(slider("maxPortalSize", 2, 21, 1, () -> config.maxPortalSize, v -> config.maxPortalSize = v, Integer::toString));
		rows.addChild(percentSlider("portalParticles", () -> config.portalParticles, v -> config.portalParticles = v));
		rows.addChild(percentSlider("streamedPortalOpacity", () -> config.streamedPortalOpacity, v -> config.streamedPortalOpacity = v));
		rows.addChild(CycleButton.onOffBuilder(config.offlinePreviews)
				.withTooltip(value -> Tooltip.create(Component.translatable("netherview.config.offlinePreviews.tooltip")))
				.create(Component.translatable("netherview.config.offlinePreviews"), (button, value) -> config.offlinePreviews = value));
		rows.addChild(percentSlider("offlinePortalOpacity", () -> config.offlinePortalOpacity, v -> config.offlinePortalOpacity = v));
		rows.addChild(slider("offlineCacheMb", 0, CACHE_SIZES_MB.length - 1, 1, () -> cacheIndex(config.offlineCacheMb),
				v -> config.offlineCacheMb = CACHE_SIZES_MB[v], v -> CACHE_SIZES_MB[v] + " MB"));

		this.layout.addToContents(grid);
		this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
	}

	@Override
	public void onClose() {
		ClientConfig.get().save();
		ClientVersioned.setScreen(this.minecraft, this.parent);
	}

	private static int cacheIndex(int megabytes) {
		for (int i = 0; i < CACHE_SIZES_MB.length; i++) {
			if (CACHE_SIZES_MB[i] >= megabytes) {
				return i;
			}
		}
		return CACHE_SIZES_MB.length - 1;
	}

	private interface DoubleGetter {
		double get();
	}

	private interface DoubleSetter {
		void set(double value);
	}

	/** 0-1 as a percentage, in steps of 5%. */
	private static AbstractSliderButton percentSlider(String key, DoubleGetter getter, DoubleSetter setter) {
		return slider(key, 0, 20, 1, () -> (int) Math.round(getter.get() * 20.0), v -> setter.set(v / 20.0), v -> (v * 5) + "%");
	}

	private static AbstractSliderButton slider(String key, int min, int max, int step, IntSupplier getter, IntConsumer setter, IntFunction<String> format) {
		Component name = Component.translatable("netherview.config." + key);
		int initial = Mth.clamp(getter.getAsInt(), min, max);
		AbstractSliderButton slider = new AbstractSliderButton(0, 0, WIDGET_WIDTH, 20, Component.empty(), (double) (initial - min) / (max - min)) {
			{
				this.updateMessage();
			}

			private int current() {
				int raw = min + (int) Math.round(this.value * (max - min));
				return Mth.clamp(Math.round((float) (raw - min) / step) * step + min, min, max);
			}

			@Override
			protected void updateMessage() {
				this.setMessage(CommonComponents.optionNameValue(name, Component.literal(format.apply(this.current()))));
			}

			@Override
			protected void applyValue() {
				setter.accept(this.current());
			}
		};
		slider.setTooltip(Tooltip.create(Component.translatable("netherview.config." + key + ".tooltip")));
		return slider;
	}
}
