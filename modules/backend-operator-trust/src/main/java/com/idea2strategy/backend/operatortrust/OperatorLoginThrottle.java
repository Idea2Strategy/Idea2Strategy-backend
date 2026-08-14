package com.idea2strategy.backend.operatortrust;

public interface OperatorLoginThrottle {
    boolean acquire(String loginKey, String sourceKey);
    void clearLogin(String loginKey);
}
