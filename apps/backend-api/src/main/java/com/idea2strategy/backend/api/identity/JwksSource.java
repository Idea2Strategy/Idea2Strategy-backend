package com.idea2strategy.backend.api.identity;

import java.io.IOException;
import java.net.URI;

@FunctionalInterface
public interface JwksSource {
    String load(URI jwksUri) throws IOException, InterruptedException;
}
