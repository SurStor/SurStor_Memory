package ai.surstor.node;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ContainerBindTest {
    @Test void defaultRemainsLoopbackOnly() {
        for (String bind : new String[]{"127.0.0.1", "::1", "localhost"})
            assertDoesNotThrow(() -> Main.validateBindAddress(bind, false));
        for (String bind : new String[]{"0.0.0.0", "::", "192.168.1.10", "example.com"})
            assertThrows(IllegalArgumentException.class, () -> Main.validateBindAddress(bind, false));
    }

    @Test void explicitFlagAllowsOnlyContainerWildcard() {
        var command = new Main.StartCommand();
        new CommandLine(command).parseArgs("--allow-container-network");
        assertTrue(command.allowContainerNetwork);
        assertDoesNotThrow(() -> Main.validateBindAddress("0.0.0.0", command.allowContainerNetwork));
        assertThrows(IllegalArgumentException.class, () -> Main.validateBindAddress("::", true));
        assertThrows(IllegalArgumentException.class, () -> Main.validateBindAddress("example.com", true));
    }
}
