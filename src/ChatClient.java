import java.awt.*;
import javax.swing.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.KeyStore;
import java.util.Optional;

import javax.net.ssl.*;

public class ChatClient {
    private enum Mode {
        LOGIN, ROOMS, CHAT
    }

    private Mode mode;

    private final String hostname;
    private final int port;

    private volatile SSLSocket socket;
    private volatile BufferedReader serverReader;
    private volatile PrintWriter serverWriter;
    private volatile String token;
    private volatile String currentRoomName;

    private final File sessionFile = new File("session.dat");

    private JFrame frame;
    private CardLayout cardLayout;
    private JPanel cards;

    // Login UI
    private JTextField userField;
    private JPasswordField passField;
    private JLabel loginStatus;

    // Rooms UI
    private DefaultListModel<String> roomListModel;
    private JList<String> roomList;

    // Chat UI
    private JTextArea messageArea;
    private JTextField inputField;
    private Thread listenerThread;

    public ChatClient(String hostname, int port) {
        this.hostname = hostname;
        this.port = port;
        initializeGui();
        loadSession(); // read token + last room (if any)
        if (token != null) {
            // we have a token: go to lobby or chat depending on saved room
            SwingUtilities.invokeLater(() -> {
                roomListModel.clear();
                if (currentRoomName != null && !currentRoomName.isBlank()) {
                    showChat();
                } else {
                    showRooms();
                    fetchRooms();
                }
            });
            // and start the reconnect logic
            new Thread(this::initialReconnect, "initial-reconnect").start();
        } else {
            SwingUtilities.invokeLater(this::showLogin);
        }
    }

    private void initializeGui() {
        frame = new JFrame("Chat Client");
        cardLayout = new CardLayout();
        cards = new JPanel(cardLayout);

        cards.add(createLoginPanel(), "LOGIN");
        cards.add(createRoomsPanel(), "ROOMS");
        cards.add(createChatPanel(), "CHAT");

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(cards);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel createLoginPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0;
        gbc.gridy = 0;
        p.add(new JLabel("Username:"), gbc);
        gbc.gridx = 1;
        userField = new JTextField(15);
        p.add(userField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        p.add(new JLabel("Password:"), gbc);
        gbc.gridx = 1;
        passField = new JPasswordField(15);
        p.add(passField, gbc);

        gbc.gridy = 2;
        gbc.gridx = 0;
        JButton loginBtn = new JButton("Login");
        loginBtn.addActionListener(e -> doAuth("AUTH"));
        p.add(loginBtn, gbc);

        gbc.gridx = 1;
        JButton regBtn = new JButton("Register");
        regBtn.addActionListener(e -> doAuth("REGISTER"));
        p.add(regBtn, gbc);

        gbc.gridy = 3;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        loginStatus = new JLabel(" ");
        loginStatus.setForeground(Color.RED);
        p.add(loginStatus, gbc);

        return p;
    }

    private JPanel createRoomsPanel() {
        JPanel p = new JPanel(new BorderLayout(10, 10));
        roomListModel = new DefaultListModel<>();
        roomList = new JList<>(roomListModel);
        JScrollPane scroll = new JScrollPane(roomList);
        scroll.setBorder(BorderFactory.createTitledBorder("Available rooms"));
        p.add(scroll, BorderLayout.CENTER);

        JPanel btns = new JPanel();
        JButton join = new JButton("Join Room");
        join.addActionListener(e -> joinRoom());
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> fetchRooms());
        JButton create = new JButton("Create Room");
        create.addActionListener(e -> createRoom());
        JButton logout = new JButton("Logout");
        logout.addActionListener(e -> doLogout());
        btns.add(join);
        btns.add(refresh);
        btns.add(create);
        btns.add(logout);

        p.add(btns, BorderLayout.SOUTH);
        return p;
    }

    private JPanel createChatPanel() {
        JPanel p = new JPanel(new BorderLayout(5, 5));
        messageArea = new JTextArea(20, 50);
        messageArea.setEditable(false);
        p.add(new JScrollPane(messageArea), BorderLayout.CENTER);

        JPanel inp = new JPanel(new BorderLayout(5, 5));
        inputField = new JTextField();
        inputField.addActionListener(e -> sendMessage());
        inp.add(inputField, BorderLayout.CENTER);

        JPanel right = new JPanel();
        JButton send = new JButton("Send");
        send.addActionListener(e -> sendMessage());
        JButton exit = new JButton("Exit Room");
        exit.addActionListener(e -> exitRoom());
        JButton sim = new JButton("Simulate Disconnect");
        sim.addActionListener(e -> {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        });
        right.add(send);
        right.add(exit);
        right.add(sim);
        inp.add(right, BorderLayout.EAST);

        p.add(inp, BorderLayout.SOUTH);
        return p;
    }

    // ----- AUTH & SESSION PERSISTENCE -----

    private void doAuth(String cmd) {
        String u = userField.getText().trim();
        String p = new String(passField.getPassword()).trim();
        if (u.isEmpty() || p.isEmpty()) {
            loginStatus.setText("Username/password required.");
            return;
        }
        loginStatus.setText("Connecting…");

        new Thread(() -> {
            try {
                openSocket();
                serverReader.readLine(); // welcome
                serverReader.readLine(); // prompt

                serverWriter.println(cmd + " " + u + " " + p);
                String resp = serverReader.readLine();
                if ("AUTH_OK".equals(resp)) {
                    String tokLine = serverReader.readLine(); // "TOKEN xxx"
                    token = tokLine.substring("TOKEN ".length()).trim();
                    saveSession();
                    SwingUtilities.invokeLater(() -> {
                        showRooms();
                        fetchRooms();
                    });
                    startListener();
                } else {
                    SwingUtilities.invokeLater(() -> loginStatus.setText("Error: " + resp));
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> loginStatus.setText("Conn error: " + ex.getMessage()));
            }
        }, "auth-thread").start();
    }

    private void loadSession() {
        if (!sessionFile.exists())
            return;
        try (BufferedReader r = new BufferedReader(new FileReader(sessionFile))) {
            String tokLine = r.readLine();
            String roomLine = r.readLine();
            String pidLine = r.readLine();

            if (tokLine == null)
                return;

            long ownerPid = -1;
            if (pidLine != null && !pidLine.isBlank()) {
                try {
                    ownerPid = Long.parseLong(pidLine.trim());
                } catch (NumberFormatException ignored) {
                }
            }

            long myPid = ProcessHandle.current().pid();
            boolean takeOver = false;

            if (ownerPid == myPid) {
                takeOver = true;
            } else if (ownerPid != -1) {
                Optional<ProcessHandle> other = ProcessHandle.of(ownerPid);
                if (other.map(ph -> !ph.isAlive()).orElse(true)) {
                    takeOver = true;
                }
            } else {
                takeOver = true;
            }

            if (takeOver && !tokLine.isBlank()) {
                token = tokLine.trim();
                currentRoomName = (roomLine != null && !roomLine.isBlank())
                        ? roomLine.trim()
                        : null;
                saveSession();
            }
        } catch (IOException e) {
            sessionFile.delete();
        }
    }

    private void saveSession() {
        try (PrintWriter w = new PrintWriter(new FileWriter(sessionFile))) {
            w.println(token != null ? token : "");
            w.println(currentRoomName != null ? currentRoomName : "");
            w.println(ProcessHandle.current().pid());
        } catch (IOException ignored) {
        }
    }

    // ----- NETWORK & RECONNECT -----

    private void openSocket() throws Exception {
        char[] pass = "changeit".toCharArray();
        KeyStore ts = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream("certs/clienttruststore.jks")) {
            ts.load(fis, pass);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        SSLSocketFactory sf = ctx.getSocketFactory();
        socket = (SSLSocket) sf.createSocket(hostname, port);
        socket.setEnabledProtocols(new String[] { "TLSv1.2", "TLSv1.3" });
        socket.startHandshake();
        serverReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        serverWriter = new PrintWriter(socket.getOutputStream(), true);
    }

    private void initialReconnect() {
        // 1s delay to let UI render
        try {
            Thread.sleep(1000);
        } catch (Exception ignored) {
        }
        attemptReconnect();
    }

    private void startListener() {
        listenerThread = new Thread(() -> {
            try {
                String line;
                while ((line = serverReader.readLine()) != null) {
                    final String msg = line;
                    SwingUtilities.invokeLater(() -> {
                        if (mode == Mode.ROOMS) {
                            if (!msg.isBlank())
                                roomListModel.addElement(msg);
                        } else if (mode == Mode.CHAT) {
                            messageArea.append(msg + "\n");
                        }
                    });
                }
                // clean close
                SwingUtilities.invokeLater(() -> messageArea.append("ℹ️ Server closed connection.\n"));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> messageArea.append("⚠️ Connection lost, reconnecting...\n"));
                attemptReconnect();
            }
        }, "listener-thread");
        listenerThread.start();
    }

    private void attemptReconnect() {
        if (token == null)
            return;
        while (true) {
            try {
                Thread.sleep(2000);
                openSocket();
                serverReader.readLine();
                serverReader.readLine();
                serverWriter.println("TOKEN " + token);
                String r = serverReader.readLine();
                if ("RECONNECT_OK".equals(r)) {
                    saveSession(); // in case room changed
                    SwingUtilities.invokeLater(() -> {
                        if (currentRoomName != null) {
                            messageArea.append("✅ Reconnected to room \"" + currentRoomName + "\".\n");
                            showChat();
                        } else {
                            messageArea.append("✅ Reconnected to lobby.\n");
                            roomListModel.clear();
                            showRooms();
                            fetchRooms();
                        }
                    });
                    startListener();
                    return;
                } else if ("INVALID_TOKEN".equalsIgnoreCase(r)) {
                    // token expired — drop back to login
                    token = null;
                    sessionFile.delete();
                    SwingUtilities.invokeLater(() -> {
                        loginStatus.setText("Session expired, please log in again.");
                        showLogin();
                    });
                    return;
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ----- ROOM FLOWS -----

    private void fetchRooms() {
        SwingUtilities.invokeLater(roomListModel::clear);
        serverWriter.println("LIST");
    }

    private void joinRoom() {
        String r = roomList.getSelectedValue();
        if (r == null)
            return;
        currentRoomName = r;
        saveSession();
        serverWriter.println(r);
        SwingUtilities.invokeLater(() -> {
            messageArea.setText("");
            showChat();
        });
    }

    private void createRoom() {
        String n = JOptionPane.showInputDialog(
                frame,
                "Enter new room name (prefix 'AI_' for AI rooms):",
                "Create Room",
                JOptionPane.PLAIN_MESSAGE);
        if (n == null || n.isBlank())
            return;
        currentRoomName = n.trim();
        saveSession();
        serverWriter.println(currentRoomName);
        SwingUtilities.invokeLater(() -> {
            messageArea.setText("");
            showChat();
        });
    }

    private void exitRoom() {
        serverWriter.println("EXIT");
        currentRoomName = null;
        saveSession();
        SwingUtilities.invokeLater(() -> showRooms());
        fetchRooms();
    }

    private void doLogout() {
        if (serverWriter != null) {
            serverWriter.println("LOGOUT");
        }
        token = null;
        currentRoomName = null;
        sessionFile.delete();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        if (listenerThread != null)
            listenerThread.interrupt();
        SwingUtilities.invokeLater(() -> {
            loginStatus.setText("Logged out.");
            userField.setText("");
            passField.setText("");
            roomListModel.clear();
            messageArea.setText("");
            showLogin();
        });
    }

    private void sendMessage() {
        String t = inputField.getText().trim();
        if (t.isEmpty())
            return;
        serverWriter.println(t);
        SwingUtilities.invokeLater(() -> inputField.setText(""));
    }

    // ----- UI STATE -----

    private void showLogin() {
        mode = Mode.LOGIN;
        cardLayout.show(cards, "LOGIN");
    }

    private void showRooms() {
        mode = Mode.ROOMS;
        cardLayout.show(cards, "ROOMS");
    }

    private void showChat() {
        mode = Mode.CHAT;
        cardLayout.show(cards, "CHAT");
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java ChatClient <host> <port>");
            return;
        }
        SwingUtilities.invokeLater(() -> new ChatClient(args[0], Integer.parseInt(args[1])));
    }
}
