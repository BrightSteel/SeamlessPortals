package dev.ryan.seamlessportals.client.mixin;

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
	float seamlessportals$getHudFov();

	@Accessor("depthFar")
	float seamlessportals$getDepthFar();

	@Accessor("fov")
	void seamlessportals$setFov(float fov);

	@Accessor("hudFov")
	void seamlessportals$setHudFov(float hudFov);

	@Accessor("depthFar")
	void seamlessportals$setDepthFar(float depthFar);

	@Accessor("initialized")
	void seamlessportals$setInitialized(boolean initialized);

	@Accessor("detached")
	void seamlessportals$setDetached(boolean detached);

	@Invoker("setupPerspective")
	void seamlessportals$setupPerspective(float zNear, float zFar, float fov, float width, float height);

	@Invoker("prepareCullFrustum")
	void seamlessportals$prepareCullFrustum(Matrix4fc modelViewMatrix, Matrix4f projectionMatrixForCulling, Vec3 cameraPos);

	@Invoker("createProjectionMatrixForCulling")
	Matrix4f seamlessportals$createProjectionMatrixForCulling();
}
