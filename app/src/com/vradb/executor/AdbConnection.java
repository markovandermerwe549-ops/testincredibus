package com.vradb.executor;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal ADB-over-TCP client (the classic "adb tcpip 5555" transport).
 * Supports RSA authentication and running shell commands one at a time.
 */
final class AdbConnection {
    interface Listener {
        void onStatus(String msg);
        void onOutput(String text);
    }

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int A_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int nextLocalId = 1;
    private final Object writeLock = new Object();
    private volatile int curLocal, curRemote;
    private volatile boolean connected;
    private String deviceBanner = "";

    boolean isConnected() {
        return connected;
    }

    String banner() {
        return deviceBanner;
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    void connect(String host, int port, AdbKey key, Listener l) throws Exception {
        close();
        l.onStatus("Connecting to " + host + ":" + port + " ...");
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 8000);
        s.setTcpNoDelay(true);
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();

        send(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".getBytes("UTF-8"));

        boolean sentSignature = false;
        boolean sentPublicKey = false;
        // Long timeout so the user has time to accept the "Allow USB debugging?" prompt.
        s.setSoTimeout(90000);
        while (true) {
            Msg m = read();
            if (m.cmd == A_CNXN) {
                deviceBanner = new String(m.data, "UTF-8").replace("\u0000", "");
                break;
            } else if (m.cmd == A_AUTH && m.arg0 == AUTH_TOKEN) {
                if (!sentSignature) {
                    l.onStatus("Authenticating ...");
                    send(A_AUTH, AUTH_SIGNATURE, 0, key.signToken(m.data));
                    sentSignature = true;
                } else if (!sentPublicKey) {
                    l.onStatus("Accept the 'Allow debugging' prompt on the target device (tick 'Always allow') ...");
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.adbPublicKey("vr-adb-executor@quest"));
                    sentPublicKey = true;
                } else {
                    throw new IOException("Device rejected this app's key");
                }
            } else {
                throw new IOException("Unexpected reply during handshake: 0x" + Integer.toHexString(m.cmd));
            }
        }
        s.setSoTimeout(0);
        connected = true;
    }

    /** Runs a shell command, streaming its output to the listener. Blocks until the command ends. */
    void shell(String command, Listener l) throws Exception {
        if (!connected) throw new IOException("Not connected");
        int localId = nextLocalId++;
        curLocal = localId;
        curRemote = 0;
        send(A_OPEN, localId, 0, ("shell:" + command + "\u0000").getBytes("UTF-8"));
        try {
            shellLoop(localId, l);
        } finally {
            curLocal = 0;
            curRemote = 0;
        }
    }

    private void shellLoop(int localId, Listener l) throws Exception {
        int remoteId = 0;
        while (true) {
            Msg m = read();
            if (m.arg1 != localId && m.cmd != A_CNXN) continue; // stale packet for another stream
            if (m.cmd == A_OKAY) {
                remoteId = m.arg0;
                curRemote = remoteId;
            } else if (m.cmd == A_WRTE) {
                remoteId = m.arg0;
                curRemote = remoteId;
                if (m.data.length > 0) l.onOutput(new String(m.data, "UTF-8"));
                send(A_OKAY, localId, remoteId, new byte[0]);
            } else if (m.cmd == A_CLSE) {
                if (remoteId != 0) send(A_CLSE, localId, remoteId, new byte[0]);
                return;
            }
        }
    }

    /** Asks the device to stop the running command (e.g. logcat or top). */
    void stopCurrent() {
        int local = curLocal, remote = curRemote;
        if (local != 0 && remote != 0) {
            try {
                send(A_CLSE, local, remote, new byte[0]);
            } catch (IOException ignored) {
            }
        }
    }

    void close() {
        connected = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        socket = null;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte b : data) sum += (b & 0xff);
        bb.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        bb.put(data);
        synchronized (writeLock) {
            out.write(bb.array());
            out.flush();
        }
    }

    private Msg read() throws IOException {
        byte[] hdr = readFully(24);
        ByteBuffer bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = bb.getInt();
        m.arg0 = bb.getInt();
        m.arg1 = bb.getInt();
        int len = bb.getInt();
        bb.getInt(); // checksum
        int magic = bb.getInt();
        if (magic != ~m.cmd) throw new IOException("Bad packet from device");
        if (len < 0 || len > 1024 * 1024) throw new IOException("Bad packet length " + len);
        m.data = readFully(len);
        return m;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                connected = false;
                throw new EOFException("Connection closed by device");
            }
            off += r;
        }
        return buf;
    }

}
