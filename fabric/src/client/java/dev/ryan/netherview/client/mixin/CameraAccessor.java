package dev.ryan.netherview.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets {@code PortalCamera} place itself directly instead of following an entity. */
@Mixin(Camera.class)
public interface CameraAccessor {
	@Accessor("hudFov")
	float netherview$getHudFov();

	@Accessor("depthFar")
	float netherview$getDepthFar();

	@Accessor("fov")
	void netherview$setFov(float fov);

	@Accessor("hudFov")
	void netherview$setHudFov(float hudFov);

	@Accessor("depthFar")
	void netherview$setDepthFar(float depthFar);

	@Accessor("initialized")
	void netherview$setInitialized(boolean initialized);

	@Accessor("detached")
	void netherview$setDetached(boolean detached);

	@Invoker("setupPerspective")
	void netherview$setupPerspective(float zNear, float zFar, float fov, float width, float height);

	@Invoker("prepareCullFrustum")
	void netherview$prepareCullFrustum(Matrix4fc modelViewMatrix, Matrix4f projectionMatrixForCulling, Vec3 cameraPos);

	@Invoker("createProjectionMatrixForCulling")
	Matrix4f netherview$createProjectionMatrixForCulling();
}
