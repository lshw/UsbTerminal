package cn.org.bjlx.usb_terminal;

import android.app.Activity;
import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.InvalidParameterException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TelnetSocket implements TerminalSocket {
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_BUFFER_SIZE = 4096;

    private final String host;
    private final int port;
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private SerialListener listener;
    private Thread readThread;
    private volatile boolean disconnecting;
    private ExecutorService writeExecutor;

    TelnetSocket(Context context, String host, int port) {
        if (context instanceof Activity) {
            throw new InvalidParameterException("expected non UI context");
        }
        this.host = host;
        this.port = port;
    }

    @Override
    public String getName() {
        return host + ":" + port;
    }

    @Override
    public void connect(SerialListener listener) throws IOException {
        this.listener = listener;
        try {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
        socket.setTcpNoDelay(true);
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
        writeExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "telnet-writer"));
        startReader();
        } catch (IOException e) {
            disconnect();
            String detail = e.getMessage();
            if (detail == null || detail.trim().isEmpty()) {
                detail = e.getClass().getSimpleName();
            }
            throw new IOException("Telnet " + host + ":" + port + " - " + detail, e);
        }
    }

    @Override
    public void disconnect() {
        disconnecting = true;
        listener = null;
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        if (writeExecutor != null) {
            writeExecutor.shutdownNow();
            writeExecutor = null;
        }
        closeQuietly(inputStream);
        closeQuietly(outputStream);
        closeQuietly(socket);
        inputStream = null;
        outputStream = null;
        socket = null;
    }

    @Override
    public void write(byte[] data) throws IOException {
        OutputStream out = outputStream;
        ExecutorService executor = writeExecutor;
        if (out == null || executor == null) {
            throw new IOException("not connected");
        }
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        executor.execute(() -> {
            try {
                out.write(copy);
                out.flush();
            } catch (Exception e) {
                SerialListener currentListener = listener;
                if (!disconnecting && currentListener != null) {
                    currentListener.onSerialIoError(e instanceof IOException ? e : new IOException(e));
                }
            }
        });
    }

    private void startReader() {
        readThread = new Thread(() -> {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    InputStream in = inputStream;
                    if (in == null) {
                        break;
                    }
                    int len = in.read(buffer);
                    if (len < 0) {
                        throw new IOException("remote closed");
                    }
                    if (len == 0) {
                        continue;
                    }
                    SerialListener currentListener = listener;
                    if (currentListener != null) {
                        byte[] chunk = new byte[len];
                        System.arraycopy(buffer, 0, chunk, 0, len);
                        currentListener.onSerialRead(chunk);
                    }
                }
            } catch (Exception e) {
                SerialListener currentListener = listener;
                if (!disconnecting && currentListener != null) {
                    currentListener.onSerialIoError(e instanceof IOException ? e : new IOException(e));
                }
            } finally {
                disconnect();
            }
        }, "telnet-reader");
        readThread.start();
    }

    private void closeQuietly(Socket value) {
        if (value != null) {
            try {
                value.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void closeQuietly(InputStream value) {
        if (value != null) {
            try {
                value.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void closeQuietly(OutputStream value) {
        if (value != null) {
            try {
                value.close();
            } catch (Exception ignored) {
            }
        }
    }
}
