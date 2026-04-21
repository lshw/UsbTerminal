package cn.org.bjlx.usb_terminal;

import java.io.IOException;

interface TerminalSocket {
    String getName();
    void connect(SerialListener listener) throws IOException;
    void disconnect();
    void write(byte[] data) throws IOException;
}
