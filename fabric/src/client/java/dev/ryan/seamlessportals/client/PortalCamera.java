package dev.ryan.seamlessportals.client;

import dev.ryan.seamlessportals.client.mixin.CameraAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A camera on the far side of a portal. Instead of following an entity, it is placed
 * each frame at the player camera's position carried through the portal pair, and it
 * copies the player camera's lens (fov, far plane) so both images line up.
 */
public final class PortalCamera extends Camera {

	public void place(Camera main, ClientLevel level, Vec3 position, Quaternionf orientation) {
		this.place(main, level, position, orientation, null);
	}

	/**
	 * @param cullProjection if given, the projection whose frustum culls sections and entities
	 *                       (e.g. narrowed to a portal's part of the screen); otherwise the
	 *                       camera's usual one
	 */
	public void place(Camera main, ClientLevel level, Vec3 position, Quaternionf orientation, Matrix4f cullProjection) {
		Minecraft minecraft = Minecraft.getInstance();
		CameraAccessor self = (CameraAccessor) (Object) this;
		CameraAccessor mainLens = (CameraAccessor) main;

		this.setLevel(level);
		if (minecraft.player != null) {
			this.setEntity(minecraft.player);
		}
		self.seamlessportals$setDetached(main.isDetached());
		self.seamlessportals$setFov(main.getFov());
		self.seamlessportals$setHudFov(mainLens.seamlessportals$getHudFov());
		self.seamlessportals$setDepthFar(mainLens.seamlessportals$getDepthFar());

		// Yaw and pitch for whatever reads them; then the exact orientation, which a portal pair
		// can roll (wall to floor), so the image lines up with the player's view.
		Vector3f forward = new Vector3f(0.0F, 0.0F, -1.0F).rotate(orientation);
		this.setRotation((float) Math.toDegrees(Math.atan2(-forward.x, forward.z)), (float) Math.toDegrees(Math.asin(Math.clamp(-forward.y, -1.0F, 1.0F))));
		this.rotation().set(orientation);
		((Vector3f) this.forwardVector()).set(0.0F, 0.0F, -1.0F).rotate(orientation);
		((Vector3f) this.upVector()).set(0.0F, 1.0F, 0.0F).rotate(orientation);
		((Vector3f) this.leftVector()).set(-1.0F, 0.0F, 0.0F).rotate(orientation);
		this.setPosition(position);

		self.seamlessportals$prepareCullFrustum(this.getViewRotationMatrix(new Matrix4f()),
				cullProjection != null ? cullProjection : self.seamlessportals$createProjectionMatrixForCulling(), position);
		self.seamlessportals$setupPerspective(
				Camera.PROJECTION_Z_NEAR, mainLens.seamlessportals$getDepthFar(), main.getFov(),
				minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
		self.seamlessportals$setInitialized(true);
	}
}
