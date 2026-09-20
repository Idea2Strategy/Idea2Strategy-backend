package com.idea2strategy.backend.application.identity;

public interface DeviceCodeMaterialPort {
    DeviceCodeMaterial issue();

    String digestDeviceCode(String deviceCode);

    String digestUserCode(String userCode);
}
