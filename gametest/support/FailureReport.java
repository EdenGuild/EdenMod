package support;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Listed last in fabric.mod.json: reports every suite failure recorded by {@link Failures}. */
public final class FailureReport implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		Failures.throwIfAny();
	}
}
