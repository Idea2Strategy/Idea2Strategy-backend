package com.idea2strategy.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.util.Map;

final class OperatorCredentialProvisionCommand {
    private OperatorCredentialProvisionCommand() {}

    static ObjectNode execute(Arguments arguments, Map<String, String> environment, InputStream stdin) {
        return OperatorCredentialCommandSupport.execute(arguments, environment, stdin, false);
    }
}
