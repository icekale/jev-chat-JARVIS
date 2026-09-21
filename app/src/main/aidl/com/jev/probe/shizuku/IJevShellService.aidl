package com.jev.probe.shizuku;

interface IJevShellService {
    void destroy() = 16777114;
    String allowRestrictedSettings(String packageName) = 1;
    String putSecureSetting(String name, String value) = 2;
}
