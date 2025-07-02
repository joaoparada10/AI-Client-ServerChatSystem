import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable {
    private final Socket sock;
    private BufferedReader in;
    private PrintWriter out;
    public String username;
    private ChatRoom room;
    private Session session;

    public ClientHandler(Socket s) throws IOException {
        this.sock = s;
        this.in = new BufferedReader(new InputStreamReader(s.getInputStream()));
        this.out = new PrintWriter(s.getOutputStream(), true);
    }

    @Override
    public void run() {
        boolean reconnecting = false;
        try {
            out.println("Welcome to ChatServer.");
            // AUTH / REGISTER / TOKEN
            while (true) {
                out.println("AUTH <user> <pw>  or  REGISTER <user> <pw>  or  TOKEN <token>");
                String line = in.readLine();
                if (line == null)
                    return;
                String[] parts = line.split(" ", 2);
                String cmd = parts[0].toUpperCase();

                if ("TOKEN".equals(cmd)) {
                    if (parts.length < 2) {
                        out.println("INVALID_TOKEN");
                        continue;
                    }
                    Session s = ChatServer.getSession(parts[1].trim());
                    if (s == null) {
                        out.println("INVALID_TOKEN");
                        continue;
                    }
                    this.session = s;
                    this.username = s.username;
                    s.setHandler(this);
                    out.println("RECONNECT_OK");
                    reconnecting = true;
                    break;
                }

                String[] tok = line.split(" ", 3);
                if (tok.length < 3) {
                    out.println("INVALID_COMMAND");
                    continue;
                }
                String user = tok[1], pass = tok[2];

                if ("AUTH".equalsIgnoreCase(tok[0])) {
                    if (!ChatServer.authenticate(user, pass)) {
                        out.println("AUTH_FAIL");
                        continue;
                    }
                    if (ChatServer.isLoggedIn(user)) {
                        out.println("ALREADY_LOGGED_IN");
                        continue;
                    }
                } else if ("REGISTER".equalsIgnoreCase(tok[0])) {
                    if (!ChatServer.registerUser(user, pass)) {
                        out.println("EXISTS");
                        continue;
                    }
                } else {
                    out.println("INVALID_COMMAND");
                    continue;
                }

                // success -> new session
                this.username = user;
                String tokn = ChatServer.createSession(user, this);
                this.session = ChatServer.getSession(tokn);
                out.println("AUTH_OK");
                out.println("TOKEN " + tokn);
                break;
            }

            // If reconnecting && were in a room, re-join
            if (reconnecting && session.roomName != null) {
                room = ChatServer.getOrCreateRoom(session.roomName);
                room.addClient(this);
                out.println("JOINED " + session.roomName);
            }

            // Main loop
            while (true) {
                // --- Lobby / room-selection phase ---
                if (room == null) {
                    String cmd = in.readLine();
                    if (cmd == null)
                        return;
                    cmd = cmd.trim();

                    if ("LOGOUT".equalsIgnoreCase(cmd)) {
                        ChatServer.removeSession(session);
                        out.println("LOGOUT_OK");
                        return;
                    }

                    if ("LIST".equalsIgnoreCase(cmd)) {
                        for (String r : ChatServer.getRoomNames())
                            out.println(r);
                        out.println(); // blank line = end
                        continue;
                    }
                    // join or create
                    room = ChatServer.getOrCreateRoom(cmd);
                    room.addClient(this);
                    session.roomName = cmd;
                    out.println("JOINED " + cmd);
                }

                // --- Chat phase ---
                String msg;
                while ((msg = in.readLine()) != null) {
                    msg = msg.trim();

                    // exit room
                    if ("EXIT".equalsIgnoreCase(msg)) {
                        room.removeClient(this);
                        session.roomName = null;
                        room = null;
                        break;
                    }

                    // in-room logout
                    if ("LOGOUT".equalsIgnoreCase(msg)) {
                        room.removeClient(this);
                        session.roomName = null;
                        room = null;
                        ChatServer.removeSession(session);
                        out.println("LOGOUT_OK");
                        return;
                    }

                    if ("LIST".equalsIgnoreCase(msg)) {
                        continue;
                    }

                    // broadcast
                    room.userMessage(username + ": " + msg, this);
                }

                if (msg == null) {
                    // client disconnected uncleanly
                    return;
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            // cleanup on any exit
            if (room != null) {
                room.removeClient(this);
            }
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    public String getUsername() {
        return username;
    }

    public void sendMessage(String m) {
        out.println(m);
    }
}
