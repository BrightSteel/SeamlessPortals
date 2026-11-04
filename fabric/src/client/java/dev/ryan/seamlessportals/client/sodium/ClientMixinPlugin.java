package dev.ryan.seamlessportals.client.sodium;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Leaves out the client mixins on vanilla code that Sodium replaces outright: they would fail to
 * apply (and Sodium does that work its own way). Asked of the loader: no game classes load this early.
 */
public final class ClientMixinPlugin implements IMixinConfigPlugin {
	/** Mixins on methods Sodium overwrites. */
	private static final Set<String> REPLACED_BY_SODIUM = Set.of("RemoteCullTerrainMixin");

	private boolean sodium;

	@Override
	public void onLoad(String mixinPackage) {
		this.sodium = FabricLoader.getInstance().isModLoaded("sodium");
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return !(this.sodium && REPLACED_BY_SODIUM.contains(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)));
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
