package dev.ryan.seamlessportals.net;

/**
 * How far a portal's opening is pulled in from the edges of its blocks, in sixteenths of a
 * block, on each side as seen from its front: {@code left} and {@code right} along its width,
 * {@code bottom} and {@code top} along its height. For an opening narrower than whole blocks,
 * such as a doorway in a model.
 */
public record PortalInsets(int left, int right, int bottom, int top) {
	public static final PortalInsets NONE = new PortalInsets(0, 0, 0, 0);

	public boolean isNone() {
		return this.equals(NONE);
	}
}
