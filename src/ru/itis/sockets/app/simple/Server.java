package ru.itis.sockets.app.simple;

import ru.itis.sockets.app.simple.entities.ClientHandler;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

public class Server {

    private final ServerSocket serverSocket;
    private int serverPort;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public Server() throws IOException {
        initializeServer();
        this.serverSocket = new ServerSocket(serverPort);
        System.out.println("Server started on port " + serverPort);
    }

    public void initializeServer() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("Enter the server port: ");
            serverPort = scanner.nextInt();
            try {
                ServerSocket testSocket = new ServerSocket(serverPort);
                testSocket.close();
                break;
            } catch (IOException e) {
                System.out.println("Port " + serverPort + " is busy. Please choose another port.");
            }
        }
    }

    public void startServer() {
        try {
            while (running.get() && !serverSocket.isClosed()) {
                serverSocket.setSoTimeout(1000);
                try {
                    Socket socket = serverSocket.accept();
                    System.out.println("A new client has connected");
                    ClientHandler clientHandler = new ClientHandler(socket);

                    Thread thread = new Thread(clientHandler);
                    thread.start();
                } catch (SocketTimeoutException e) {
                    // timeout is good
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                System.out.println("Server error: " + e.getMessage());
            }
            stopServer();
        }
    }

    public void stopServer() {
        running.set(false);
        ClientHandler.notifyServerShutdown();
        closeServerSocket();
    }

    public void closeServerSocket() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
                System.out.println("Server socket closed");
            }
        } catch (IOException e) {
            System.out.println("Error closing server socket");
        }
    }

    public static void main(String[] args) throws IOException {
        Server server = new Server();
        server.startServer();
    }
}