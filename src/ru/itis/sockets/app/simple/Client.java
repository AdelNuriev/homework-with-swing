package ru.itis.sockets.app.simple;

import ru.itis.sockets.app.simple.entities.SocketMessage;
import ru.itis.sockets.app.simple.utils.MessageUtils;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class Client {

    private final MessageUtils messageUtils;

    private Socket socket;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private String username;
    private int serverPort;
    private volatile boolean connected = false;

    public Client() {
        initializeClient();
        messageUtils = new MessageUtils();
    }

    public void initializeClient() {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Please enter your username: ");
        username = scanner.nextLine();

        while (true) {
            System.out.print("Enter the server port: ");
            try {
                serverPort = scanner.nextInt();
                scanner.nextLine();

                if (createSocket()) break;
            } catch (Exception e) {
                System.out.println("Invalid server port");
                scanner.nextLine();
            }
        }


    }

    public boolean createSocket() {
        try {
            socket = new Socket("localhost", serverPort);
            connected = true;
            System.out.println("Connected to server successfully!");
            initializeStreams();
            return true;
        } catch (IOException e) {
            System.out.println("Could not connect to server or server does not exist");
            System.out.println("Please check if server is running on port " + serverPort);
            return false;
        }
    }

    public void initializeStreams() {
        try {
            this.dataOutputStream = new DataOutputStream(socket.getOutputStream());
            this.dataInputStream = new DataInputStream(socket.getInputStream());
        } catch (IOException e) {
            System.out.println("Error initializing streams");
            closeEverything();
        }
    }

    public void sendMessage() {
        if (!connected) {
            System.out.println("Cannot send messages - not connected to server");
            return;
        }

        try {
            messageUtils.sendMessage(dataOutputStream, username);

            Scanner scanner = new Scanner(System.in);
            while (connected && socket.isConnected()) {
                String message = scanner.nextLine();

                if (!SocketMessage.isValidLength(message)) {
                    System.out.println("Message too long! Maximum length is " +
                            SocketMessage.MAX_MESSAGE_LENGTH + " characters.");
                    continue;
                }

                String fullMessage = username + ": " + message;
                messageUtils.sendMessage(dataOutputStream, fullMessage);
            }
        } catch (IOException e) {
            System.out.println("Connection to server lost!");
            closeEverything();
        }
    }

    public void listenForMessage() {
        if (!connected) {
            System.out.println("Cannot listen for messages - not connected to server");
            return;
        }

        new Thread(() -> {
            while (connected && socket.isConnected()) {
                try {
                    String receivedMessage = messageUtils.readMessage(dataInputStream);
                    System.out.println(receivedMessage);
                } catch (IOException e) {
                    if (connected) {
                        System.out.println("Server connection lost!");
                    }
                    closeEverything();
                    break;
                }
            }
        }).start();
    }

    public void closeEverything() {
        connected = false;
        try {
            if (socket != null) socket.close();
            if (dataInputStream != null) dataInputStream.close();
            if (dataOutputStream != null) dataOutputStream.close();
        } catch (IOException e) {
            // ignore
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public static void main(String[] args) {
        Client client = new Client();

        if (client.isConnected()) {
            client.listenForMessage();
            client.sendMessage();
        } else {
            System.out.println("Failed to start client. Exiting...");
        }
    }
}