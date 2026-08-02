package com.idea2strategy.backend.application.delegation;

@FunctionalInterface
public interface DelegatedCredentialMaterialPort {
    DelegatedCredentialMaterial issue();
}
