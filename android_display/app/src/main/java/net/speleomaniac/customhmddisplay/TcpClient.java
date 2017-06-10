package net.speleomaniac.customhmddisplay;


import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


class TcpClient extends Thread {

    interface PacketReceiveListener {
        void onPacketReceived(DisplayActivity.VirtualPacketTypes type, byte[] data, int size);
    }

    private PacketReceiveListener packetReceiveListener = null;
    private final int Port;
    private final String Server;

    private Socket driverSocket = null;
    private InputStream input = null;
    private OutputStream output = null;

    boolean IsConnected;

    private boolean isTcpRunning = false;

    private byte[] outHeader = null; //size and type only
    private ByteBuffer outHeaderBuffer = null;

    private byte[] inHeader = null; //size and type only
    private ByteBuffer inHeaderBuffer = null;

    private byte[] encodedBuffer = new byte[1024 * 1024 * 10]; //10M



    TcpClient(PacketReceiveListener listener, String server, int port) {
        IsConnected = false;
        Server = server;
        Port = port;
        packetReceiveListener = listener;

        outHeader = new byte[8];
        outHeaderBuffer = ByteBuffer.wrap(outHeader);
        outHeaderBuffer.order(ByteOrder.LITTLE_ENDIAN);

        inHeader = new byte[8];
        inHeaderBuffer = ByteBuffer.wrap(inHeader);
        inHeaderBuffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    private int outCount = 0;
    private short packetSequence = 0;

    private class PacketSender extends Thread {
        private final Object isFull = new Object();
        private final Object isEmpty = new Object();
        private DisplayActivity.VirtualPacketTypes _type = DisplayActivity.VirtualPacketTypes.Invalid;
        private byte[] _data = null;
        private int _len = 0;
        private boolean _hasData = false;
        private boolean _running = false;
        void sendPacket(DisplayActivity.VirtualPacketTypes type, byte[] data, int len) {
            synchronized(isFull) {
                _data = data;
                _len = len;
                _type = type;
                _hasData = true;
                isFull.notify();
            }
        }

        @Override
        public synchronized void start() {
            _running = true;
            super.start();
        }

        @Override
        public void run() {
            while (_running) {
                try {
                    synchronized (isFull) {
                        while (!_hasData)
                            isFull.wait();
                        internalSendPacket(_type, _data, _len);
                        outCount++;
                        if ((outCount % 100) == 0) {
                            Log.d("RPS", "OuCount = " + outCount);
                            outCount = 0;
                        }
                        _hasData = false;
                    }
                }
                catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        }

        @Override
        public void interrupt() {
            _running = false;
            try {
                join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private PacketSender _packetSender = null;

    private final Object socketLock = new Object();

    void sendPacket(DisplayActivity.VirtualPacketTypes type, byte[] data, int len) {
        if (_packetSender != null)
            _packetSender.sendPacket(type, data, len);
    }

    private void internalSendPacket(DisplayActivity.VirtualPacketTypes type, byte[] data, int len) {
        if (output == null) return;
        try {
            synchronized (socketLock) { //wait send finish before sending another packet
                outHeaderBuffer.rewind();
                outHeaderBuffer.putInt(len);
                outHeaderBuffer.putShort(type.getValue());
                outHeaderBuffer.putShort(packetSequence);
                output.write(outHeader, 0, 8);
                output.write(data, 0, len);
            }
        } catch (IOException e) {
            //e.printStackTrace();
        }
    }

    private void closeDriverSocket() {
        if (driverSocket != null) {
            try {
                packetReceiveListener.onPacketReceived(DisplayActivity.VirtualPacketTypes.Invalid, null, 0);
                driverSocket.close();
                input.close();
                output.close();
            } catch (Exception e) {
                //e.printStackTrace();
            }
        }
        driverSocket = null;
        input = null;
        output = null;
        IsConnected = false;
    }

    private boolean _reconnect = false;

    void reconnect() {
        _reconnect = true;
    }

    void disconnect() {
        if (!isTcpRunning)
            return;
        isTcpRunning = false;
        try {
            join();
        } catch (Exception e) {
            //e.printStackTrace();
        }
        if (_packetSender != null) {
            _packetSender.interrupt();
            _packetSender = null;
        }
        closeDriverSocket();
    }

    //boolean isRunning = false;

    @Override
    public synchronized void start() {
        if (isTcpRunning)
            return;
        isTcpRunning = true;
        if (_packetSender == null) {
            _packetSender = new PacketSender();
            _packetSender.start();
        }

        super.start();
    }

    @Override
    public void run() {
        while (isTcpRunning) {
            try {
                _reconnect = false;
                long lastPacketReceive = System.currentTimeMillis();
                InetAddress serverAddr = InetAddress.getByName(Server);
                driverSocket = new Socket(serverAddr, Port);
                input = driverSocket.getInputStream();
                output = driverSocket.getOutputStream();
                IsConnected = true;

                while (isTcpRunning && !_reconnect && IsConnected && driverSocket != null) {
                    int size = 0;
                    short type = 0;
                    //short sequence = 0;
                    int avail = 0;
                        avail = input.available();
                    if (avail >= 8) {
                        int read = input.read(inHeader);
                        inHeaderBuffer.rewind();
                        size = inHeaderBuffer.getInt();
                        type = inHeaderBuffer.getShort();
                        packetSequence = inHeaderBuffer.getShort();
                    }

                    if (size == 0) {
                        //check disconnect (adb forward or reverse mode keeps connection )
                        if (System.currentTimeMillis() - lastPacketReceive > 2000) {
                            closeDriverSocket();
                            continue;
                        }
                    }

                    int remain = size;
                    int pos = 0;
                    while (isTcpRunning && IsConnected && remain > 0) {
                        avail = 0;
                        if (input == null)
                            break;
                        avail = input.available();
                        if (avail >= 0) {
                            avail = Math.min(remain, avail);
                            int read = 0;
                            read = input.read(encodedBuffer, pos, avail);
                            if (read < 0) {
                                closeDriverSocket();
                                break;
                            }
                            pos += read;
                            remain -= read;
                            if (remain == 0) {
                                lastPacketReceive = System.currentTimeMillis();
                                packetReceiveListener.onPacketReceived(DisplayActivity.VirtualPacketTypes.valueOf(type), encodedBuffer, size);
                            }
                        } else {
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            closeDriverSocket();
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
                //e.printStackTrace();
            }
        }
    }
}