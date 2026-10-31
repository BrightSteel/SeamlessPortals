package dev.ryan.netherview.client;

import dev.ryan.netherview.client.mixin.CameraAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * A camera on the far side of a portal. Instead of following an entity, it is placed
 * each frame at the player camera's position carried through the portal pair, and it
 * copies the player camera's lens (fov, far plane) so both images line up.
 */
public final class PortalCamera extends Camera {

	public void place(Camera main, ClientLevel level, Vec3 position, float yRot, float xRot) {
		Minecraft minecraft = Minecraft.getInstance();
		CameraAccessor self = (CameraAccessor) (Object) this;
		CameraAccessor mainLens = (CameraAccessor) main;

		this.setLevel(level);
		if (minecraft.player != null) {
			this.setEntity(minecraft.player);
		}
		self.netherview$setDetached(main.isDetached());
		self.netherview$setFov(main.getFov());
		self.netherview$setHudFov(mainLens.netherview$getHudFov());
		self.netherview$setDepthFar(mainLens.netherview$getDepthFar());

		this.setRotation(yRot, xRot);
		this.setPosition(position);

		self.netherview$prepareCullFrustum(
				this.getViewRotationMatrix(new Matrix4f()), self.netherview$createProjectionMatrixForCulling(), position);
		self.netherview$setupPerspective(
				Camera.PROJECTION_Z_NEAR, mainLens.netherview$getDepthFar(), main.getFov(),
				minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
		self.netherview$setInitialized(true);
	}
}
