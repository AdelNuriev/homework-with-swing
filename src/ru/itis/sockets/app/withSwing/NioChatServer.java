package ru.itis.sockets.app.withSwing;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class NioChatServer extends JFrame {
    private static final int DEFAULT_PORT = 9001;
    private static final int MAX_CLIENTS = 10;

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private Thread serverThread;
    private boolean running = false;

    private final Map<SocketChannel, ClientInfo> clients = new ConcurrentHashMap<>();
    private final Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> bannedIPs = new ConcurrentHashMap<>();

    private JTextArea logArea;
    private JTextArea chatArea;
    private JTextField inputField;
    private JButton startButton, stopButton, banButton, kickButton, createRoomButton;
    private JList<String> clientList;
    private DefaultListModel<String> clientListModel;
    private JList<String> roomList;
    private DefaultListModel<String> roomListModel;
    private JSpinner portSpinner;
    private JTextField serverNameField;
    private JLabel statusLabel;

    public NioChatServer() {
        initUI();
    }

    private void initUI() {
        setTitle("NIO Chat Server - Multi-Room Support");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 650);
        setLayout(new BorderLayout());

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        serverNameField = new JTextField("Admin", 10);
        portSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_PORT, 1024, 65535, 1));

        startButton = new JButton("Start Server");
        stopButton = new JButton("Stop Server");
        stopButton.setEnabled(false);
        createRoomButton = new JButton("Create Room");
        createRoomButton.setEnabled(false);

        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        createRoomButton.addActionListener(e -> createRoom());

        controlPanel.add(new JLabel("Server Name:"));
        controlPanel.add(serverNameField);
        controlPanel.add(new JLabel("Port:"));
        controlPanel.add(portSpinner);
        controlPanel.add(startButton);
        controlPanel.add(stopButton);
        controlPanel.add(createRoomButton);

        statusLabel = new JLabel("Server not running");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusLabel, BorderLayout.WEST);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(controlPanel, BorderLayout.NORTH);
        topPanel.add(statusPanel, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        JPanel leftPanel = new JPanel(new GridLayout(3, 1));

        clientListModel = new DefaultListModel<>();
        clientList = new JList<>(clientListModel);
        clientList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane clientScroll = new JScrollPane(clientList);
        clientScroll.setBorder(BorderFactory.createTitledBorder("Connected Clients (0/" + MAX_CLIENTS + ")"));

        roomListModel = new DefaultListModel<>();
        roomList = new JList<>(roomListModel);
        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane roomScroll = new JScrollPane(roomList);
        roomScroll.setBorder(BorderFactory.createTitledBorder("Chat Rooms"));

        logArea = new JTextArea(8, 20);
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Server Log"));

        leftPanel.add(clientScroll);
        leftPanel.add(roomScroll);
        leftPanel.add(logScroll);

        JPanel rightPanel = new JPanel(new BorderLayout());

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(BorderFactory.createTitledBorder("Global Chat"));

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        inputField.setEnabled(false);
        JButton sendButton = new JButton("Send");

        inputField.setDocument(new javax.swing.text.PlainDocument() {
            @Override
            public void insertString(int offs, String str, javax.swing.text.AttributeSet a)
                    throws javax.swing.text.BadLocationException {
                if (getLength() + str.length() <= ChatProtocol.MAX_MESSAGE_LENGTH) {
                    super.insertString(offs, str, a);
                } else {
                    Toolkit.getDefaultToolkit().beep();
                }
            }
        });

        inputField.addActionListener(e -> sendGroupMessage());
        sendButton.addActionListener(e -> sendGroupMessage());

        inputPanel.add(new JLabel("Admin Message (" + ChatProtocol.MAX_MESSAGE_LENGTH + " chars max):"),
                BorderLayout.WEST);
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        rightPanel.add(chatScroll, BorderLayout.CENTER);
        rightPanel.add(inputPanel, BorderLayout.SOUTH);

        mainSplit.setLeftComponent(leftPanel);
        mainSplit.setRightComponent(rightPanel);
        mainSplit.setDividerLocation(400);

        add(mainSplit, BorderLayout.CENTER);

        JPanel adminPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        kickButton = new JButton("Kick Selected");
        banButton = new JButton("Ban Selected");
        kickButton.setEnabled(false);
        banButton.setEnabled(false);

        kickButton.addActionListener(e -> kickSelectedClient());
        banButton.addActionListener(e -> banSelectedClient());

        adminPanel.add(kickButton);
        adminPanel.add(banButton);

        add(adminPanel, BorderLayout.SOUTH);

        clientList.addListSelectionListener(e -> {
            boolean hasSelection = !clientList.isSelectionEmpty();
            kickButton.setEnabled(hasSelection);
            banButton.setEnabled(hasSelection);
        });

        setLocationRelativeTo(null);
    }

    private void startServer() {
        int port = (Integer) portSpinner.getValue();
        String serverName = serverNameField.getText().trim();

        if (serverName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter server name",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(port));

            selector = Selector.open();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            running = true;
            serverThread = new Thread(this::runServer);
            serverThread.setDaemon(true);
            serverThread.start();

            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            createRoomButton.setEnabled(true);
            inputField.setEnabled(true);
            portSpinner.setEnabled(false);

            updateStatus("Server running on port " + port);
            log("Server started on port " + port);

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to start server: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void runServer() {
        while (running) {
            try {
                selector.select();
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        acceptClient(key);
                    } else if (key.isReadable()) {
                        handleClientMessage(key);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    log("Server error: " + e.getMessage());
                }
            }
        }
    }

    private void acceptClient(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);

        log("New connection: " + client.getRemoteAddress());
    }

    private void handleClientMessage(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(4096);

        try {
            int bytesRead = client.read(buffer);
            if (bytesRead == -1) {
                disconnectClient(client);
                return;
            }

            if (bytesRead > 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    buffer.mark();
                    String[] parsed = ChatProtocol.parseMessage(buffer);

                    if (parsed != null) {
                        byte type = Byte.parseByte(parsed[0]);
                        byte subType = Byte.parseByte(parsed[1]);
                        String data = parsed[2];

                        processClientMessage(client, type, subType, data);
                    } else {
                        buffer.reset();
                        break;
                    }
                }
            }

        } catch (IOException e) {
            disconnectClient(client);
        } catch (IllegalArgumentException e) {
            log("Invalid message from client: " + e.getMessage());
        }
    }

    private void processClientMessage(SocketChannel client, byte type, byte subType, String data) throws IOException {
        switch (type) {
            case ChatProtocol.CONNECTION_MANAGEMENT:
                handleConnection(client, subType, data);
                break;
            case ChatProtocol.GROUP_MESSAGE:
                handleGroupMessage(client, data);
                break;
            case ChatProtocol.PRIVATE_MESSAGE:
                handlePrivateMessage(client, subType, data);
                break;
            case ChatProtocol.ROOM_MANAGEMENT:
                handleRoomManagement(client, subType, data);
                break;
            case ChatProtocol.INITIALIZATION:
                handleInitialization(client, subType);
                break;
        }
    }

    private void handleConnection(SocketChannel client, byte subType, String data) throws IOException {
        if (subType == ChatProtocol.CONNECT_REQUEST) {
            String nickname = data;
            String clientIP = client.socket().getInetAddress().getHostAddress();

            log(nickname + " connecting from " + clientIP);

            if (bannedIPs.containsKey(clientIP)) {
                sendMessage(client, ChatProtocol.CONNECTION_MANAGEMENT,
                        ChatProtocol.CONNECT_ERROR, "You are banned: " + bannedIPs.get(clientIP));
                client.close();
                return;
            }

            for (ClientInfo info : clients.values()) {
                if (info.nickname.equals(nickname)) {
                    sendMessage(client, ChatProtocol.CONNECTION_MANAGEMENT,
                            ChatProtocol.CONNECT_ERROR, "Nickname already taken");
                    client.close();
                    return;
                }
            }

            if (clients.size() >= MAX_CLIENTS) {
                sendMessage(client, ChatProtocol.CONNECTION_MANAGEMENT,
                        ChatProtocol.CONNECT_ERROR,
                        "Server is full! Maximum " + MAX_CLIENTS + " users allowed.");
                client.close();
                return;
            }

            if (nickname.length() > ChatProtocol.MAX_NICKNAME_LENGTH) {
                sendMessage(client, ChatProtocol.CONNECTION_MANAGEMENT,
                        ChatProtocol.CONNECT_ERROR,
                        "Nickname too long! Max " + ChatProtocol.MAX_NICKNAME_LENGTH + " characters.");
                client.close();
                return;
            }

            ClientInfo clientInfo = new ClientInfo(nickname, clientIP);
            clients.put(client, clientInfo);
            updateClientList();

            sendMessage(client, ChatProtocol.CONNECTION_MANAGEMENT,
                    ChatProtocol.CONNECT_SUCCESS, "Welcome!");

            broadcastClientUpdate(ChatProtocol.ADD_CLIENT, nickname, client);

            String message = getTimestamp() + " " + nickname + " joined the chat";
            chatArea.append(message + "\n");
            broadcastGroupMessage(message, client);

            log(nickname + " connected");

        } else if (subType == ChatProtocol.DISCONNECT) {
            disconnectClient(client);
        }
    }

    private void handleInitialization(SocketChannel client, byte subType) throws IOException {
        if (subType == ChatProtocol.INIT_REQUEST) {
            ClientInfo clientInfo = clients.get(client);
            if (clientInfo == null) return;

            // Отправляем данные инициализации
            String usersList = buildUsersList();
            String roomsList = buildRoomsList();

            sendMessage(client, ChatProtocol.INITIALIZATION,
                    ChatProtocol.INIT_DATA, usersList + ";" + roomsList);

            // Отправляем завершение инициализации
            sendMessage(client, ChatProtocol.INITIALIZATION,
                    ChatProtocol.INIT_COMPLETE, "");
        }
    }

    private String buildUsersList() {
        StringBuilder list = new StringBuilder();
        list.append(serverNameField.getText());

        for (ClientInfo info : clients.values()) {
            list.append(";").append(info.nickname);
        }

        return list.toString();
    }

    private String buildRoomsList() {
        StringBuilder list = new StringBuilder();
        for (String roomName : rooms.keySet()) {
            if (!list.isEmpty()) list.append(";");
            list.append(roomName);
        }
        return list.toString();
    }

    private void handleGroupMessage(SocketChannel sender, String message) {
        ClientInfo senderInfo = clients.get(sender);
        if (senderInfo == null) return;

        if (message.length() > ChatProtocol.MAX_MESSAGE_LENGTH) {
            return;
        }

        String formatted = getTimestamp() + " " + senderInfo.nickname + ": " + message;
        chatArea.append(formatted + "\n");

        broadcastGroupMessage(formatted, sender);
    }

    private void handlePrivateMessage(SocketChannel client, byte subType, String data) throws IOException {
        String[] parts = data.split("\\|", 2);
        if (parts.length < 2) return;

        String target = parts[0];
        String message = parts[1];
        ClientInfo sender = clients.get(client);

        if (sender == null) return;

        if (message.length() > ChatProtocol.MAX_MESSAGE_LENGTH) {
            return;
        }

        SocketChannel targetClient = findClientByName(target);
        if (targetClient != null && targetClient.isConnected()) {
            if (subType == ChatProtocol.PRIVATE_MSG) {
                sendMessage(targetClient, ChatProtocol.PRIVATE_MESSAGE,
                        ChatProtocol.PRIVATE_MSG, sender.nickname + "|" + message);
            }
        }
    }

    private void handleRoomManagement(SocketChannel client, byte subType, String data) throws IOException {
        ClientInfo clientInfo = clients.get(client);
        if (clientInfo == null) return;

        String[] parts = data.split("\\|", 3);

        switch (subType) {
            case ChatProtocol.CREATE_ROOM:
                if (parts.length >= 2) {
                    String roomName = parts[0];
                    String creator = parts[1];

                    if (roomName.length() > ChatProtocol.MAX_ROOM_NAME_LENGTH) {
                        sendMessage(client, ChatProtocol.ROOM_MANAGEMENT,
                                ChatProtocol.CREATE_ROOM,
                                roomName + "|" + creator + "|false");
                        return;
                    }

                    if (rooms.size() >= ChatProtocol.MAX_ROOMS) {
                        sendMessage(client, ChatProtocol.ROOM_MANAGEMENT,
                                ChatProtocol.CREATE_ROOM,
                                roomName + "|" + creator + "|false");
                        log("Cannot create room " + roomName + ": maximum rooms reached");
                        return;
                    }

                    if (!rooms.containsKey(roomName)) {
                        ChatRoom room = new ChatRoom(roomName, creator);
                        rooms.put(roomName, room);

                        sendMessage(client, ChatProtocol.ROOM_MANAGEMENT,
                                ChatProtocol.CREATE_ROOM,
                                roomName + "|" + creator + "|true");

                        broadcastRoomList();
                        log("Room created: " + roomName + " by " + creator);
                    } else {
                        sendMessage(client, ChatProtocol.ROOM_MANAGEMENT,
                                ChatProtocol.CREATE_ROOM,
                                roomName + "|" + creator + "|false");
                    }
                }
                break;

            case ChatProtocol.JOIN_ROOM:
                if (parts.length >= 2) {
                    String roomName = parts[0];
                    String userName = parts[1];

                    ChatRoom room = rooms.get(roomName);
                    if (room != null) {
                        room.addUser(userName);
                        clientInfo.joinedRooms.add(roomName);

                        for (String msg : room.getHistory()) {
                            sendMessage(client, ChatProtocol.ROOM_MANAGEMENT,
                                    ChatProtocol.ROOM_MESSAGE,
                                    roomName + "|" + msg);
                        }

                        broadcastToRoom(roomName, ChatProtocol.ROOM_MESSAGE,
                                roomName + "|" + userName + " joined the room");

                        log(userName + " joined room: " + roomName);
                    }
                }
                break;

            case ChatProtocol.ROOM_MESSAGE:
                if (parts.length >= 3) {
                    String roomName = parts[0];
                    String sender = parts[1];
                    String message = parts[2];

                    if (message.length() > ChatProtocol.MAX_MESSAGE_LENGTH) {
                        return;
                    }

                    ChatRoom room = rooms.get(roomName);
                    if (room != null && room.hasUser(sender)) {
                        String formatted = getTimestamp() + " " + sender + ": " + message;
                        room.addMessage(formatted);

                        broadcastToRoom(roomName, ChatProtocol.ROOM_MESSAGE,
                                roomName + "|" + sender + "|" + message);
                    }
                }
                break;

            case ChatProtocol.LEAVE_ROOM:
                if (parts.length >= 2) {
                    String roomName = parts[0];
                    String userName = parts[1];

                    ChatRoom room = rooms.get(roomName);
                    if (room != null) {
                        room.removeUser(userName);
                        clientInfo.joinedRooms.remove(roomName);

                        if (room.getUserCount() == 0) {
                            rooms.remove(roomName);
                            broadcastRoomList();
                            log("Room deleted (empty): " + roomName);
                        } else {
                            broadcastToRoom(roomName, ChatProtocol.ROOM_MESSAGE,
                                    roomName + "|" + userName + " left the room");
                        }

                        log(userName + " left room: " + roomName);
                    }
                }
                break;
        }
    }

    private void createRoom() {
        String roomName = JOptionPane.showInputDialog(this,
                "Enter room name (max " + ChatProtocol.MAX_ROOM_NAME_LENGTH + " chars):",
                "Create Room", JOptionPane.PLAIN_MESSAGE);

        if (roomName != null && !roomName.trim().isEmpty()) {
            roomName = roomName.trim();

            if (roomName.length() > ChatProtocol.MAX_ROOM_NAME_LENGTH) {
                JOptionPane.showMessageDialog(this,
                        "Room name too long! Max " + ChatProtocol.MAX_ROOM_NAME_LENGTH + " characters.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (!rooms.containsKey(roomName)) {
                ChatRoom room = new ChatRoom(roomName, "Admin");
                rooms.put(roomName, room);

                roomListModel.addElement(roomName);
                log("Admin created room: " + roomName);
                broadcastRoomList();
            } else {
                JOptionPane.showMessageDialog(this,
                        "Room '" + roomName + "' already exists",
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void sendGroupMessage() {
        String message = inputField.getText().trim();
        if (message.isEmpty()) return;

        if (message.length() > ChatProtocol.MAX_MESSAGE_LENGTH) {
            inputField.setText("");
            return;
        }

        String serverName = serverNameField.getText().trim();
        String formatted = getTimestamp() + " " + serverName + ": " + message;

        chatArea.append(formatted + "\n");
        inputField.setText("");

        broadcastGroupMessage(formatted, null);
    }

    private void kickSelectedClient() {
        String selected = clientList.getSelectedValue();
        if (selected == null) return;

        SocketChannel client = findClientByName(selected);
        if (client != null) {
            try {
                sendMessage(client, ChatProtocol.CONNECTION_MANAGEMENT,
                        ChatProtocol.CONNECT_ERROR, "You have been kicked by admin");
                disconnectClient(client);

                chatArea.append(getTimestamp() + " " + selected + " was kicked\n");
                log("Kicked client: " + selected);
            } catch (IOException e) {
                log("Error kicking client: " + e.getMessage());
            }
        }
    }

    private void banSelectedClient() {
        String selected = clientList.getSelectedValue();
        if (selected == null) return;

        SocketChannel client = findClientByName(selected);
        if (client != null) {
            try {
                String ip = client.socket().getInetAddress().getHostAddress();
                bannedIPs.put(ip, selected);

                sendMessage(client, ChatProtocol.CONNECTION_MANAGEMENT,
                        ChatProtocol.CONNECT_ERROR, "You have been banned by admin");
                disconnectClient(client);

                chatArea.append(getTimestamp() + " " + selected + " was banned\n");
                log("Banned client: " + selected + " (IP: " + ip + ")");
            } catch (IOException e) {
                log("Error banning client: " + e.getMessage());
            }
        }
    }

    private void disconnectClient(SocketChannel client) throws IOException {
        ClientInfo clientInfo = clients.remove(client);
        if (clientInfo != null) {
            for (String roomName : clientInfo.joinedRooms) {
                ChatRoom room = rooms.get(roomName);
                if (room != null) {
                    room.removeUser(clientInfo.nickname);

                    if (room.getUserCount() == 0) {
                        rooms.remove(roomName);
                        roomListModel.removeElement(roomName);
                    } else {
                        broadcastToRoom(roomName, ChatProtocol.ROOM_MESSAGE,
                                roomName + "|" + clientInfo.nickname + " left the room");
                    }
                }
            }

            updateClientList();
            broadcastClientUpdate(ChatProtocol.REMOVE_CLIENT, clientInfo.nickname, client);
            broadcastRoomList();

            String message = getTimestamp() + " " + clientInfo.nickname + " left the chat";
            chatArea.append(message + "\n");
            broadcastGroupMessage(message, null);

            log(clientInfo.nickname + " disconnected");
        }

        if (client.isOpen()) {
            client.close();
        }
    }

    private void updateClientList() {
        SwingUtilities.invokeLater(() -> {
            clientListModel.clear();
            clientListModel.addElement(serverNameField.getText() + " (Server)");
            clients.values().forEach(info -> clientListModel.addElement(info.nickname));

            int onlineCount = clients.size();
            clientList.setBorder(BorderFactory.createTitledBorder(
                    "Connected Clients (" + onlineCount + "/" + MAX_CLIENTS + ")"));
        });
    }

    private void updateStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(status);
        });
    }

    private void broadcastClientUpdate(byte updateType, String nickname, SocketChannel exclude) throws IOException {
        ByteBuffer buffer = ChatProtocol.createMessage(
                ChatProtocol.CLIENT_LIST_MANAGEMENT, updateType, nickname);

        for (SocketChannel client : clients.keySet()) {
            if (client != exclude && client.isConnected()) {
                buffer.rewind();
                client.write(buffer);
            }
        }
    }

    private void broadcastGroupMessage(String message, SocketChannel exclude) {
        ByteBuffer buffer = ChatProtocol.createMessage(
                ChatProtocol.GROUP_MESSAGE, (byte)0x02, message);

        for (SocketChannel client : clients.keySet()) {
            if (client != exclude && client.isConnected()) {
                try {
                    buffer.rewind();
                    client.write(buffer);
                } catch (IOException e) {
                    // Игнорируем ошибки
                }
            }
        }
    }

    private void broadcastRoomList() {
        String roomsList = buildRoomsList();
        ByteBuffer buffer = ChatProtocol.createMessage(
                ChatProtocol.ROOM_MANAGEMENT,
                ChatProtocol.ROOM_LIST,
                roomsList);

        for (SocketChannel client : clients.keySet()) {
            if (client.isConnected()) {
                try {
                    buffer.rewind();
                    client.write(buffer);
                } catch (IOException e) {
                    // Игнорируем ошибки
                }
            }
        }

        SwingUtilities.invokeLater(() -> {
            roomListModel.clear();
            rooms.keySet().forEach(roomListModel::addElement);
        });
    }

    private void broadcastToRoom(String roomName, byte subType, String data) {
        ChatRoom room = rooms.get(roomName);
        if (room == null) return;

        ByteBuffer buffer = ChatProtocol.createMessage(
                ChatProtocol.ROOM_MANAGEMENT, subType, data);

        for (String userName : room.getUsers()) {
            SocketChannel client = findClientByName(userName);
            if (client != null && client.isConnected()) {
                try {
                    buffer.rewind();
                    client.write(buffer);
                } catch (IOException e) {
                    // Игнорируем ошибки
                }
            }
        }
    }

    private void sendMessage(SocketChannel client, byte type, byte subType, String data) throws IOException {
        ByteBuffer buffer = ChatProtocol.createMessage(type, subType, data);
        client.write(buffer);
    }

    private SocketChannel findClientByName(String name) {
        for (Map.Entry<SocketChannel, ClientInfo> entry : clients.entrySet()) {
            if (entry.getValue().nickname.equals(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void stopServer() {
        running = false;

        try {
            if (selector != null) selector.wakeup();

            for (SocketChannel client : clients.keySet()) {
                if (client.isOpen()) {
                    try {
                        sendMessage(client, ChatProtocol.CONNECTION_MANAGEMENT,
                                ChatProtocol.CONNECT_ERROR, "Server is shutting down");
                        client.close();
                    } catch (IOException e) {
                        // Игнорируем
                    }
                }
            }
            clients.clear();
            rooms.clear();

            if (selector != null) selector.close();
            if (serverChannel != null) serverChannel.close();

            if (serverThread != null) {
                serverThread.join(1000);
            }

            SwingUtilities.invokeLater(() -> {
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                createRoomButton.setEnabled(false);
                inputField.setEnabled(false);
                portSpinner.setEnabled(true);
                clientListModel.clear();
                roomListModel.clear();

                updateStatus("Server stopped");
                log("Server stopped");
            });

        } catch (Exception e) {
            log("Error stopping server: " + e.getMessage());
        }
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(getTimestamp() + " " + message + "\n");
        });
    }

    private String getTimestamp() {
        return java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static class ClientInfo {
        String nickname;
        String ipAddress;
        Set<String> joinedRooms;

        ClientInfo(String nickname, String ipAddress) {
            this.nickname = nickname;
            this.ipAddress = ipAddress;
            this.joinedRooms = new HashSet<>();
        }
    }

    private static class ChatRoom {
        private String name;
        private String creator;
        private Set<String> users;
        private List<String> messageHistory;
        private static final int MAX_HISTORY = 100;

        ChatRoom(String name, String creator) {
            this.name = name;
            this.creator = creator;
            this.users = new HashSet<>();
            this.messageHistory = new ArrayList<>();
            this.users.add(creator);
        }

        void addUser(String user) {
            users.add(user);
        }

        void removeUser(String user) {
            users.remove(user);
        }

        boolean hasUser(String user) {
            return users.contains(user);
        }

        void addMessage(String message) {
            messageHistory.add(message);
            if (messageHistory.size() > MAX_HISTORY) {
                messageHistory.remove(0);
            }
        }

        List<String> getHistory() {
            return new ArrayList<>(messageHistory);
        }

        Set<String> getUsers() {
            return new HashSet<>(users);
        }

        int getUserCount() {
            return users.size();
        }

        String getName() {
            return name;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new NioChatServer().setVisible(true);
        });
    }
}