package com.idea2strategy.backend.application.operatorbootstrap;

public interface OperatorBootstrapPort {
    OperatorBootstrapResult apply(OperatorBootstrapManifest manifest, String manifestHash);
}
