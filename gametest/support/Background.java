package support;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Makes the test client a polite guest on a real desktop: silent always, and out of sight on request. */
public final class Background {
	private Background() {
	}

	/** True if the client renders in software (Mesa's llvmpipe on CI), false on a real GPU. */
	public static boolean isSoftwareRendering(ClientGameTestContext context) {
		String renderer = context.computeOnClient(client -> GL11.glGetString(GL11.GL_RENDERER));
		String name = renderer == null ? "" : renderer.toLowerCase(java.util.Locale.ROOT);
		return name.contains("llvmpipe") || name.contains("softpipe") || name.contains("swrast");
	}

	/**
	 * Mutes the game and records which renderer is in use. With {@code -Pgametest.background=true} it
	 * also hides the game window, so a run on a desktop with a GPU does not put a Minecraft window in
	 * front of the user. Without the flag the window stays visible, which is what you want when
	 * watching a run.
	 *
	 * <p>Hiding is safe because Minecraft draws into an off-screen render target: screenshots and
	 * measurements are unaffected. The window is also created hidden, see {@code WindowMixin}.
	 */
	public static void prepare(ClientGameTestContext context) {
		boolean hide = Boolean.getBoolean("edenmod.gametest.background");
		context.runOnClient(client -> {
			if (hide) {
				GLFW.glfwHideWindow(client.getWindow().handle());
			}
			client.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
			float volume = client.options.getSoundSourceVolume(SoundSource.MASTER);
			if (volume != 0.0f) {
				throw new IllegalStateException("The master volume is " + volume + " after muting it");
			}
			// The build prints this, so it is visible whether a run used the GPU or fell back to software.
			System.out.println("[gametest] GL renderer: " + GL11.glGetString(GL11.GL_RENDERER));
		});
	}
}
