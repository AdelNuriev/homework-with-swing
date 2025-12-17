package ru.itis.sockets.app.withSwing;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NioChatClient extends JFrame {
    private SocketChannel socketChannel;
    private Selector selector;
    private Thread clientThread;
    private boolean connected = false;
    private boolean initialized = false;

    private String nickname;
    private String serverAddress;
    private int serverPort;

    private final Queue<ByteBuffer> sendQueue = new ConcurrentLinkedQueue<>();

    private JTextField nicknameField, serverField, portField;
    private JButton connectButton, disconnectButton, createRoomButton, joinRoomButton, sendButton;
    private JTextArea globalChatArea;
    private JTextField inputField;
    private JList<String> userList;
    private DefaultListModel<String> userListModel;
    private JList<String> roomList;
    private DefaultListModel<String> roomListModel;
    private JTabbedPane chatTabs;

    private final Map<String, JTextArea> roomChats = new HashMap<>();
    private final Map<String, String> currentRoom = new HashMap<>();

    public NioChatClient() {
        initUI();
    }

    private void initUI() {
        setTitle("NIO Chat Client - Multi-Room Chat");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 700);
        setLayout(new BorderLayout());

        // Панель подключения
        JPanel connectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        nicknameField = new JTextField("", 10);
        serverField = new JTextField("localhost", 10);
        portField = new JTextField("9001", 5);

        connectButton = new JButton("Connect");
        disconnectButton = new JButton("Disconnect");
        disconnectButton.setEnabled(false);

        connectButton.addActionListener(e -> connectToServer());
        disconnectButton.addActionListener(e -> disconnectFromServer());

        connectPanel.add(new JLabel("Nickname:"));
        connectPanel.add(nicknameField);
        connectPanel.add(new JLabel("Server:"));
        connectPanel.add(serverField);
        connectPanel.add(new JLabel("Port:"));
        connectPanel.add(portField);
        connectPanel.add(connectButton);
        connectPanel.add(disconnectButton);

        add(connectPanel, BorderLayout.NORTH);

        // Основная панель с разделителями
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        // Левая панель - списки пользователей и комнат
        JPanel leftPanel = new JPanel(new GridLayout(2, 1));

        // Список пользователей
        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setBorder(BorderFactory.createTitledBorder("Online Users (" +
                ChatProtocol.MAX_CLIENTS + " max)"));

        // Список комнат
        roomListModel = new DefaultListModel<>();
        roomList = new JList<>(roomListModel);
        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane roomScroll = new JScrollPane(roomList);
        roomScroll.setBorder(BorderFactory.createTitledBorder("Available Rooms"));

        // Панель управления комнатами
        JPanel roomControlPanel = new JPanel(new FlowLayout());
        createRoomButton = new JButton("Create Room");
        joinRoomButton = new JButton("Join Room");
        createRoomButton.setEnabled(false);
        joinRoomButton.setEnabled(false);

        createRoomButton.addActionListener(e -> createRoom());
        joinRoomButton.addActionListener(e -> joinSelectedRoom());
        roomList.addListSelectionListener(e -> {
            joinRoomButton.setEnabled(!roomList.isSelectionEmpty());
        });

        roomControlPanel.add(createRoomButton);
        roomControlPanel.add(joinRoomButton);

        JPanel roomPanel = new JPanel(new BorderLayout());
        roomPanel.add(roomScroll, BorderLayout.CENTER);
        roomPanel.add(roomControlPanel, BorderLayout.SOUTH);

        leftPanel.add(userScroll);
        leftPanel.add(roomPanel);

        // Центральная панель - чаты
        chatTabs = new JTabbedPane();

        // Global Chat вкладка
        JPanel globalChatPanel = createChatPanel("Global");
        globalChatArea = (JTextArea) ((JScrollPane) ((BorderLayout)
                globalChatPanel.getLayout()).getLayoutComponent(BorderLayout.CENTER)).getViewport().getView();

        chatTabs.addTab("Global Chat", globalChatPanel);

        mainSplit.setLeftComponent(leftPanel);
        mainSplit.setRightComponent(chatTabs);
        mainSplit.setDividerLocation(250);

        add(mainSplit, BorderLayout.CENTER);

        // Панель ввода сообщения
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        inputField.setEnabled(false);
        sendButton = new JButton("Send");
        sendButton.setEnabled(false);

        inputField.addActionListener(e -> sendMessage());
        sendButton.addActionListener(e -> sendMessage());

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

        inputPanel.add(new JLabel("Message (" + ChatProtocol.MAX_MESSAGE_LENGTH + " chars max):"),
                BorderLayout.WEST);
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        add(inputPanel, BorderLayout.SOUTH);

        setLocationRelativeTo(null);
    }

    private JPanel createChatPanel(String title) {
        JPanel panel = new JPanel(new BorderLayout());
        JTextArea chatArea = new JTextArea();
        chatArea.setEditable(false);
        JScrollPane scroll = new JScrollPane(chatArea);

        panel.add(scroll, BorderLayout.CENTER);

        if (title.equals("Global")) {
            roomChats.put("Global", chatArea);
        }

        return panel;
    }

    private void connectToServer() {
        nickname = nicknameField.getText().trim();
        serverAddress = serverField.getText().trim();

        if (nickname.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter nickname",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (nickname.length() > ChatProtocol.MAX_NICKNAME_LENGTH) {
            JOptionPane.showMessageDialog(this,
                    "Nickname too long! Max " + ChatProtocol.MAX_NICKNAME_LENGTH + " characters.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (serverAddress.isEmpty()) {
            serverAddress = "localhost";
        }

        try {
            serverPort = Integer.parseInt(portField.getText().trim());
            if (serverPort < 1024 || serverPort > 65535) {
                throw new NumberFormatException("Port must be between 1024 and 65535");
            }
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Invalid port number: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            cleanupBeforeConnect();

            socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(false);
            socketChannel.connect(new InetSocketAddress(serverAddress, serverPort));

            selector = Selector.open();
            socketChannel.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ);

            connected = true;
            initialized = false;
            clientThread = new Thread(this::runClient);
            clientThread.setDaemon(true);
            clientThread.start();

            SwingUtilities.invokeLater(() -> {
                connectButton.setEnabled(false);
                disconnectButton.setEnabled(true);
                nicknameField.setEnabled(false);
                serverField.setEnabled(false);
                portField.setEnabled(false);
                inputField.setEnabled(false);
                sendButton.setEnabled(false);
                createRoomButton.setEnabled(false);
                joinRoomButton.setEnabled(false);

                globalChatArea.append("Connecting to " + serverAddress + ":" + serverPort + "...\n");
            });

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to connect: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void cleanupBeforeConnect() {
        SwingUtilities.invokeLater(() -> {
            userListModel.clear();
            roomListModel.clear();
            roomChats.clear();
            currentRoom.clear();

            while (chatTabs.getTabCount() > 1) {
                chatTabs.removeTabAt(1);
            }

            globalChatArea.setText("");
        });
    }

    private void sendConnectRequest() {
        ByteBuffer buffer = ChatProtocol.createMessage(
                ChatProtocol.CONNECTION_MANAGEMENT,
                ChatProtocol.CONNECT_REQUEST,
                nickname
        );
        sendQueue.add(buffer);
        wakeupSelector();
    }

    private void runClient() {
        while (connected) {
            try {
                selector.select(1000);
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) continue;

                    if (key.isConnectable()) {
                        finishConnection();
                    } else if (key.isReadable()) {
                        handleServerMessage();
                    } else if (key.isWritable()) {
                        handleWrite();
                    }
                }

                if (socketChannel != null && socketChannel.isConnected() && !initialized) {
                    sendInitRequest();
                }

            } catch (IOException e) {
                if (connected) {
                    disconnectOnError("Connection lost");
                }
            }
        }
    }

    private void finishConnection() throws IOException {
        if (socketChannel.finishConnect()) {
            socketChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            sendConnectRequest();
        }
    }

    private void sendInitRequest() {
        ByteBuffer buffer = ChatProtocol.createMessage(
                ChatProtocol.INITIALIZATION,
                ChatProtocol.INIT_REQUEST,
                ""
        );
        sendQueue.add(buffer);
        wakeupSelector();
    }

    private void handleServerMessage() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        int bytesRead = socketChannel.read(buffer);

        if (bytesRead == -1) {
            disconnectOnError("Server disconnected");
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

                    processServerMessage(type, subType, data);
                } else {
                    buffer.reset();
                    break;
                }
            }
        }
    }

    private void processServerMessage(byte type, byte subType, String data) {
        SwingUtilities.invokeLater(() -> {
            try {
                switch (type) {
                    case ChatProtocol.CONNECTION_MANAGEMENT:
                        handleConnectionResponse(subType, data);
                        break;
                    case ChatProtocol.GROUP_MESSAGE:
                        globalChatArea.append(data + "\n");
                        break;
                    case ChatProtocol.PRIVATE_MESSAGE:
                        handlePrivateMessage(subType, data);
                        break;
                    case ChatProtocol.CLIENT_LIST_MANAGEMENT:
                        handleClientList(subType, data);
                        break;
                    case ChatProtocol.ROOM_MANAGEMENT:
                        handleRoomManagement(subType, data);
                        break;
                    case ChatProtocol.INITIALIZATION:
                        handleInitialization(subType, data);
                        break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void handleConnectionResponse(byte subType, String data) {
        if (subType == ChatProtocol.CONNECT_SUCCESS) {
            sendInitRequest();

        } else if (subType == ChatProtocol.CONNECT_ERROR) {
            JOptionPane.showMessageDialog(this, data, "Connection Error", JOptionPane.ERROR_MESSAGE);
            disconnectFromServer();
        }
    }

    private void handleInitialization(byte subType, String data) {
        if (subType == ChatProtocol.INIT_DATA) {
            String[] parts = data.split(";", 2);
            if (parts.length >= 1) {
                String usersData = parts[0];
                updateUserList(usersData);
            }
            if (parts.length >= 2) {
                String roomsData = parts[1];
                updateRoomList(roomsData);
            }

        } else if (subType == ChatProtocol.INIT_COMPLETE) {
            initialized = true;
            SwingUtilities.invokeLater(() -> {
                inputField.setEnabled(true);
                sendButton.setEnabled(true);
                createRoomButton.setEnabled(true);
                joinRoomButton.setEnabled(roomListModel.getSize() > 0);
            });
        }
    }

    private void handleClientList(byte subType, String data) {
        if (subType == ChatProtocol.FULL_LIST) {
            updateUserList(data);
        } else if (subType == ChatProtocol.ADD_CLIENT) {
            if (!userListModel.contains(data)) {
                userListModel.addElement(data);
            }
        } else if (subType == ChatProtocol.REMOVE_CLIENT) {
            userListModel.removeElement(data);
        }
    }

    private void handleRoomManagement(byte subType, String data) {
        String[] parts = data.split("\\|", 3);

        switch (subType) {
            case ChatProtocol.CREATE_ROOM:
                if (parts.length >= 2) {
                    String roomName = parts[0];
                    String creator = parts[1];
                    boolean success = Boolean.parseBoolean(parts.length > 2 ? parts[2] : "true");

                    if (success) {
                        addRoomToList(roomName);
                    } else {
                        JOptionPane.showMessageDialog(this,
                                "Failed to create room: " + roomName,
                                "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
                break;

            case ChatProtocol.ROOM_MESSAGE:
                if (parts.length >= 3) {
                    String roomName = parts[0];
                    String sender = parts[1];
                    String message = parts[2];

                    JTextArea roomChat = roomChats.get(roomName);
                    if (roomChat != null) {
                        roomChat.append(getTimestamp() + " " + sender + ": " + message + "\n");
                    } else {
                        if (!roomChats.containsKey(roomName)) {
                            joinRoom(roomName);
                            roomChats.get(roomName).append(getTimestamp() + " " + sender + ": " + message + "\n");
                        }
                    }
                }
                break;

            case ChatProtocol.ROOM_LIST:
                updateRoomList(data);
                break;

            case ChatProtocol.ROOM_USERS:
                if (parts.length >= 2) {
                    String roomName = parts[0];
                    String users = parts[1];
                    updateRoomUsers(roomName, users);
                }
                break;
        }
    }

    private void handlePrivateMessage(byte subType, String data) {
        String[] parts = data.split("\\|", 2);
        String sender = parts[0];

        if (subType == ChatProtocol.PRIVATE_MSG) {
            String message = parts.length > 1 ? parts[1] : "";
            globalChatArea.append("[Private from " + sender + "]: " + message + "\n");
        }
    }

    private void updateUserList(String listData) {
        SwingUtilities.invokeLater(() -> {
            userListModel.clear();
            if (listData != null && !listData.isEmpty()) {
                String[] users = listData.split(";");
                for (String user : users) {
                    if (!user.trim().isEmpty() && !userListModel.contains(user.trim())) {
                        userListModel.addElement(user.trim());
                    }
                }
            }

            int onlineCount = userListModel.getSize();
            userList.setBorder(BorderFactory.createTitledBorder(
                    "Online Users (" + onlineCount + "/" + ChatProtocol.MAX_CLIENTS + ")"));
        });
    }

    private void updateRoomList(String listData) {
        SwingUtilities.invokeLater(() -> {
            roomListModel.clear();
            if (listData != null && !listData.isEmpty()) {
                String[] rooms = listData.split(";");
                for (String room : rooms) {
                    if (!room.trim().isEmpty() && !roomListModel.contains(room.trim())) {
                        roomListModel.addElement(room.trim());
                    }
                }
            }
            joinRoomButton.setEnabled(roomListModel.getSize() > 0);
        });
    }

    private void addRoomToList(String roomName) {
        if (!roomListModel.contains(roomName)) {
            roomListModel.addElement(roomName);
            joinRoomButton.setEnabled(true);
        }
    }

    private void updateRoomUsers(String roomName, String users) {
        JTextArea roomChat = roomChats.get(roomName);
        if (roomChat != null) {
            String[] userArray = users.split(",");
            roomChat.append("[System] Users in room: " + String.join(", ", userArray) + "\n");
        }
    }

    private void createRoom() {
        if (!initialized) {
            JOptionPane.showMessageDialog(this,
                    "Please wait for initialization to complete",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

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

            ByteBuffer buffer = ChatProtocol.createMessage(
                    ChatProtocol.ROOM_MANAGEMENT,
                    ChatProtocol.CREATE_ROOM,
                    roomName + "|" + nickname
            );

            sendQueue.add(buffer);
            wakeupSelector();
        }
    }

    private void joinSelectedRoom() {
        if (!initialized) {
            JOptionPane.showMessageDialog(this,
                    "Please wait for initialization to complete",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String roomName = roomList.getSelectedValue();
        if (roomName != null) {
            joinRoom(roomName);
        }
    }

    private void joinRoom(String roomName) {
        if (roomChats.containsKey(roomName)) {
            for (int i = 0; i < chatTabs.getTabCount(); i++) {
                if (chatTabs.getTitleAt(i).equals(roomName)) {
                    chatTabs.setSelectedIndex(i);
                    return;
                }
            }
        }

        JPanel roomPanel = createChatPanel(roomName);
        JTextArea roomChatArea = (JTextArea) ((JScrollPane) ((BorderLayout)
                roomPanel.getLayout()).getLayoutComponent(BorderLayout.CENTER)).getViewport().getView();

        roomChats.put(roomName, roomChatArea);
        currentRoom.put(roomName, roomName);

        chatTabs.addTab(roomName, roomPanel);
        chatTabs.setSelectedIndex(chatTabs.getTabCount() - 1);

        ByteBuffer buffer = ChatProtocol.createMessage(
                ChatProtocol.ROOM_MANAGEMENT,
                ChatProtocol.JOIN_ROOM,
                roomName + "|" + nickname
        );

        sendQueue.add(buffer);
        wakeupSelector();

        roomChatArea.append("Joined room: " + roomName + "\n");
    }

    private void sendMessage() {
        if (!initialized) {
            JOptionPane.showMessageDialog(this,
                    "Please wait for initialization to complete",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String message = inputField.getText().trim();
        if (message.isEmpty()) return;

        if (message.length() > ChatProtocol.MAX_MESSAGE_LENGTH) {
            JOptionPane.showMessageDialog(this,
                    "Message too long! Max " + ChatProtocol.MAX_MESSAGE_LENGTH + " characters.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int tabIndex = chatTabs.getSelectedIndex();
        String tabTitle = chatTabs.getTitleAt(tabIndex);

        if (tabTitle.equals("Global Chat")) {
            ByteBuffer buffer = ChatProtocol.createMessage(
                    ChatProtocol.GROUP_MESSAGE,
                    (byte)0x01,
                    message
            );

            sendQueue.add(buffer);
            globalChatArea.append(getTimestamp() + " You: " + message + "\n");
        } else {
            String roomName = tabTitle;
            ByteBuffer buffer = ChatProtocol.createMessage(
                    ChatProtocol.ROOM_MANAGEMENT,
                    ChatProtocol.ROOM_MESSAGE,
                    roomName + "|" + nickname + "|" + message
            );

            sendQueue.add(buffer);

            JTextArea roomChat = roomChats.get(roomName);
            if (roomChat != null) {
                roomChat.append(getTimestamp() + " You: " + message + "\n");
            }
        }

        inputField.setText("");
    }

    private void handleWrite() throws IOException {
        while (!sendQueue.isEmpty()) {
            ByteBuffer buffer = sendQueue.poll();
            while (buffer.hasRemaining()) {
                socketChannel.write(buffer);
            }
        }
    }

    private void wakeupSelector() {
        if (selector != null && selector.isOpen()) {
            selector.wakeup();
        }
    }

    private void disconnectFromServer() {
        connected = false;
        initialized = false;

        if (socketChannel != null && socketChannel.isOpen()) {
            try {
                ByteBuffer buffer = ChatProtocol.createMessage(
                        ChatProtocol.CONNECTION_MANAGEMENT,
                        ChatProtocol.DISCONNECT,
                        ""
                );
                socketChannel.write(buffer);
                socketChannel.close();
            } catch (IOException e) {

            }
        }

        if (selector != null && selector.isOpen()) {
            try {
                selector.close();
            } catch (IOException e) {

            }
        }

        SwingUtilities.invokeLater(() -> {
            connectButton.setEnabled(true);
            disconnectButton.setEnabled(false);
            createRoomButton.setEnabled(false);
            joinRoomButton.setEnabled(false);
            nicknameField.setEnabled(true);
            serverField.setEnabled(true);
            portField.setEnabled(true);
            inputField.setEnabled(false);
            sendButton.setEnabled(false);
        });
    }

    private void disconnectOnError(String message) {
        SwingUtilities.invokeLater(() -> {
            disconnectFromServer();
        });
    }

    private String getTimestamp() {
        return java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new NioChatClient().setVisible(true);
        });
    }
}