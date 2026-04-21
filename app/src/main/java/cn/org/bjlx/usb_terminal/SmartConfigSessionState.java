package cn.org.bjlx.usb_terminal;

import androidx.annotation.Nullable;

final class SmartConfigSessionState {
    @Nullable
    private static String lastSsid;
    @Nullable
    private static String lastPassword;
    @Nullable
    private static String lastTelnetHost;
    private static int lastTelnetPort = 23;

    private SmartConfigSessionState() {
    }

    @Nullable
    static String getLastSsid() {
        return lastSsid;
    }

    static void setLastSsid(@Nullable String ssid) {
        lastSsid = ssid;
    }

    @Nullable
    static String getLastPassword() {
        return lastPassword;
    }

    static void setLastPassword(@Nullable String password) {
        lastPassword = password;
    }

    @Nullable
    static String getLastTelnetHost() {
        return lastTelnetHost;
    }

    static void setLastTelnetHost(@Nullable String host) {
        lastTelnetHost = host;
    }

    static int getLastTelnetPort() {
        return lastTelnetPort;
    }

    static void setLastTelnetPort(int port) {
        lastTelnetPort = port;
    }
}
