package ru.itis.sockets.app.withSwing;

import java.nio.ByteBuffer;

public class ChatProtocol {
    public static final byte CONNECTION_MANAGEMENT = 0x01;
    public static final byte GROUP_MESSAGE = 0x02;
    public static final byte PRIVATE_MESSAGE = 0x03;
    public static final byte CLIENT_LIST_MANAGEMENT = 0x04;
    public static final byte ROOM_MANAGEMENT = 0x05;
    public static final byte INITIALIZATION = 0x06;

    public static final byte CONNECT_REQUEST = 0x01;
    public static final byte DISCONNECT = 0x02;
    public static final byte CONNECT_SUCCESS = 0x03;
    public static final byte CONNECT_ERROR = 0x04;

    public static final byte ADD_CLIENT = 0x01;
    public static final byte REMOVE_CLIENT = 0x02;
    public static final byte FULL_LIST = 0x03;

    public static final byte PRIVATE_REQUEST = 0x01;
    public static final byte PRIVATE_MSG = 0x02;
    public static final byte PRIVATE_CONFIRM = 0x03;

    public static final byte CREATE_ROOM = 0x01;
    public static final byte JOIN_ROOM = 0x02;
    public static final byte LEAVE_ROOM = 0x03;
    public static final byte ROOM_MESSAGE = 0x04;
    public static final byte ROOM_LIST = 0x05;
    public static final byte ROOM_USERS = 0x06;

    public static final byte INIT_REQUEST = 0x01;
    public static final byte INIT_DATA = 0x02;
    public static final byte INIT_COMPLETE = 0x03;

    public static final int MAX_NICKNAME_LENGTH = 15;
    public static final int MAX_MESSAGE_LENGTH = 500;
    public static final int MAX_CLIENTS = 10;
    public static final int MAX_ROOM_NAME_LENGTH = 20;
    public static final int MAX_ROOMS = 20;

    public static ByteBuffer createMessage(byte type, byte subType, String data) {
        if (data == null) {
            data = "";
        }

        byte[] dataBytes = data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (dataBytes.length > 65535) {
            throw new IllegalArgumentException("Data too large: " + dataBytes.length + " bytes");
        }

        ByteBuffer buffer = ByteBuffer.allocate(4 + dataBytes.length);
        buffer.put(type);
        buffer.put(subType);
        buffer.putShort((short) dataBytes.length);
        buffer.put(dataBytes);
        buffer.flip();
        return buffer;
    }

    public static String[] parseMessage(ByteBuffer buffer) {
        if (buffer.remaining() < 4) return null;

        byte type = buffer.get();
        byte subType = buffer.get();
        short dataLength = buffer.getShort();

        if (dataLength < 0 || dataLength > 65535) {
            throw new IllegalArgumentException("Invalid data length: " + dataLength);
        }

        if (buffer.remaining() < dataLength) {
            buffer.position(buffer.position() - 4);
            return null;
        }

        byte[] data = new byte[dataLength];
        buffer.get(data);
        String message = new String(data, java.nio.charset.StandardCharsets.UTF_8);

        return new String[]{String.valueOf(type), String.valueOf(subType), message};
    }
}