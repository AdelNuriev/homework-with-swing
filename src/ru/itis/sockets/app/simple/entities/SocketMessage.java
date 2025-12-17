package ru.itis.sockets.app.simple.entities;

public class SocketMessage {
    public static final int MESSAGE_LENGTH_SIZE_IN_BYTES = 2;
    public static final int MAX_MESSAGE_LENGTH = 100;

    public static boolean isValidLength(String message) {
        return message.length() <= MAX_MESSAGE_LENGTH;
    }
}
