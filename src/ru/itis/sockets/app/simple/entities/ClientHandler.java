package ru.itis.sockets.app.simple.entities;

import ru.itis.sockets.app.simple.utils.MessageUtils;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;

public class ClientHandler implements Runnable {

    private static final int NUMBER_OF_CLIENTS = 10;
    public static ArrayList<ClientHandler> clientHandlers = new ArrayList<>(NUMBER_OF_CLIENTS);

    public static final MessageUtils MESSAGE_UTILS = new MessageUtils();

    private Socket socket;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private String clientUsername;
    private volatile boolean connected = true;

    public ClientHandler(Socket socket) {
        try {
            this.socket = socket;
            this.dataOutputStream = new DataOutputStream(socket.getOutputStream());
            this.dataInputStream = new DataInputStream(socket.getInputStream());

            this.clientUsername = MESSAGE_UTILS.readMessage(dataInputStream);
            addClient();
            broadcastMessage("SYSTEM: " + clientUsername + " has entered the chat");
        } catch (IOException e) {
            closeEverything();
        }
    }

    @Override
    public void run() {
        while (connected && socket.isConnected()) {
            try {
                String message = MESSAGE_UTILS.readMessage(dataInputStream);
                broadcastMessage(message);
            } catch (IOException e) {
                if (connected) {
                    System.out.println("Client " + clientUsername + " disconnected");
                }
                closeEverything();
                break;
            }
        }
    }

    public void broadcastMessage(String message) {
        synchronized (clientHandlers) {
            for (ClientHandler clientHandler : new ArrayList<>(clientHandlers)) {
                try {
                    if (clientHandler.connected) {
                        MESSAGE_UTILS.sendMessage(clientHandler.dataOutputStream, message);
                    }
                } catch (IOException e) {
                    clientHandler.closeEverything();
                }
            }
        }
    }

    public void removeClientHandler() {
        synchronized (clientHandlers) {
            clientHandlers.remove(this);
            if (connected) {
                broadcastMessage("SYSTEM: " + clientUsername + " has left the chat");
            }
        }
    }

    public void addClient() {
        synchronized (clientHandlers) {
            if (clientHandlers.size() < NUMBER_OF_CLIENTS) {
                clientHandlers.add(this);
                System.out.println("Client " + clientUsername + " joined. Total clients: " + clientHandlers.size());
            } else {
                System.out.println("Server is already full");
                closeEverything();
            }
        }
    }

    public void closeEverything() {
        connected = false;
        removeClientHandler();
        try {
            if (socket != null) socket.close();
            if (dataOutputStream != null) dataOutputStream.close();
            if (dataInputStream != null) dataInputStream.close();
        } catch (IOException e) {
            System.out.println("Error closing client connection");
        }
    }

    public static void notifyServerShutdown() {
        synchronized (clientHandlers) {
            for (ClientHandler clientHandler : new ArrayList<>(clientHandlers)) {
                try {
                    MESSAGE_UTILS.sendMessage(clientHandler.dataOutputStream,
                            "SYSTEM: Server is shutting down. Disconnecting...");
                    clientHandler.connected = false;
                } catch (IOException e) {
                    // ignore
                }
            }
            clientHandlers.clear();
        }
    }
}