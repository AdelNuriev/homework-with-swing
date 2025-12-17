package ru.itis.sockets.app.simple.utils;

import ru.itis.sockets.app.simple.entities.SocketMessage;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MessageUtils {

    public void sendMessage(DataOutputStream outputStream, String message) throws IOException {
        byte[] lengthBytes = new byte[SocketMessage.MESSAGE_LENGTH_SIZE_IN_BYTES];
        int dataLength = message.length();
        lengthBytes[0] = (byte) dataLength;
        lengthBytes[1] = (byte) (dataLength >> 8);

        outputStream.write(lengthBytes);
        outputStream.write(message.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    public String readMessage(DataInputStream inputStream) throws IOException {
        byte[] lengthBytes = new byte[SocketMessage.MESSAGE_LENGTH_SIZE_IN_BYTES];
        inputStream.readFully(lengthBytes);

        int messageLength = (lengthBytes[1] & 0xFF) << 8 | (lengthBytes[0] & 0xFF);
        byte[] messageData = new byte[messageLength];
        inputStream.readFully(messageData);

        return new String(messageData, StandardCharsets.UTF_8);
    }
}
