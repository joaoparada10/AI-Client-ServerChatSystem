import java.io.*;
import java.net.*;
import java.net.http.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.locks.*;
import javax.net.ssl.*;

public class ChatServer {
    private static final String USERS_FILE = "users.txt";
    private static final Map<String, ChatRoom> chatRooms = new HashMap<>();
    private static final ReentrantLock roomsLock = new ReentrantLock();
    private static final Map<String, String> users = new HashMap<>();
    private static final ReentrantLock usersLock = new ReentrantLock();
    private static final Map<String, Session> sessions = new HashMap<>();
    private static final ReentrantLock sessionsLock = new ReentrantLock();

    public static String createSession(String username, ClientHandler handler) {
        String token = UUID.randomUUID().toString();
        Session sess = new Session(username, handler);
        sessionsLock.lock();
        try {
            sessions.put(token, sess);
        } finally {
            sessionsLock.unlock();
        }
        return token;
    }

    public static Session getSession(String token) {
        sessionsLock.lock();
        try {
            Session s = sessions.get(token);
            if (s == null || s.isExpired()) {
                sessions.remove(token);
                return null;
            }
            return s;
        } finally {
            sessionsLock.unlock();
        }
    }

    public static boolean isLoggedIn(String username) {
        sessionsLock.lock();
        try {
            return sessions.values().stream()
                    .anyMatch(s -> s.username.equals(username) && !s.isExpired());
        } finally {
            sessionsLock.unlock();
        }
    }

    public static void removeSession(Session sess) {
        sessionsLock.lock();
        try {
            sessions.entrySet().removeIf(e -> e.getValue() == sess);
        } finally {
            sessionsLock.unlock();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java ChatServer <port>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        loadUsers();

        // TLS setup
        char[] pass = "changeit".toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream("certs/serverkeystore.jks")) {
            ks.load(fis, pass);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, pass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);

        try (SSLServerSocket serv = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(port)) {
            System.out.println("ChatServer listening on port " + port);
            while (true) {
                SSLSocket sock = (SSLSocket) serv.accept();
                Thread.startVirtualThread(() -> handleClient(sock));
            }
        }
    }

    private static void handleClient(SSLSocket sock) {
        try {
            sock.setEnabledProtocols(new String[] { "TLSv1.2", "TLSv1.3" });
            sock.startHandshake();
            new ClientHandler(sock).run();
        } catch (Exception e) {
            e.printStackTrace();
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void loadUsers() {
        usersLock.lock();
        try {
            File f = new File(USERS_FILE);
            if (!f.exists()) {
                f.createNewFile();
                return;
            }
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split(":", 2);
                    if (p.length == 2)
                        users.put(p[0], p[1]);
                }
            }
        } catch (IOException e) {
            System.err.println("Load users failed: " + e);
        } finally {
            usersLock.unlock();
        }
    }

    private static void saveUsers() {
        usersLock.lock();
        try (PrintWriter pw = new PrintWriter(new FileWriter(USERS_FILE))) {
            users.forEach((u, p) -> pw.println(u + ":" + p));
        } catch (IOException e) {
            System.err.println("Save users failed: " + e);
        } finally {
            usersLock.unlock();
        }
    }

    public static boolean authenticate(String u, String p) {
        usersLock.lock();
        try {
            return p.equals(users.get(u));
        } finally {
            usersLock.unlock();
        }
    }

    public static boolean registerUser(String u, String p) {
        usersLock.lock();
        try {
            if (users.containsKey(u))
                return false;
            users.put(u, p);
            saveUsers();
            return true;
        } finally {
            usersLock.unlock();
        }
    }

    public static ChatRoom getOrCreateRoom(String name) {
        roomsLock.lock();
        try {
            return chatRooms.computeIfAbsent(name, n -> new ChatRoom(n, n.startsWith("AI_")));
        } finally {
            roomsLock.unlock();
        }
    }

    public static List<String> getRoomNames() {
        roomsLock.lock();
        try {
            return new ArrayList<>(chatRooms.keySet());
        } finally {
            roomsLock.unlock();
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String extractAndUnescapeContent(String body) {
        int idx = body.indexOf("\"content\":");
        if (idx < 0)
            return null;
        int i = body.indexOf('"', idx + 10) + 1;
        StringBuilder sb = new StringBuilder();
        while (i < body.length()) {
            char c = body.charAt(i++);
            if (c == '\\' && i < body.length()) {
                char esc = body.charAt(i++);
                switch (esc) {
                    case 'u' -> {
                        String hex = body.substring(i, i + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    }
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    default -> sb.append(esc);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String getAIChatResponse(List<Message> history) {
        try {
            StringBuilder arr = new StringBuilder("[");
            for (int j = 0; j < history.size(); j++) {
                Message m = history.get(j);
                arr.append("{\"role\":\"")
                        .append(escapeJson(m.role))
                        .append("\",\"content\":\"")
                        .append(escapeJson(m.content))
                        .append("\"}");
                if (j < history.size() - 1)
                    arr.append(",");
            }
            arr.append("]");
            String payload = "{" +
                    "\"model\":\"llama3.2\"," +
                    "\"messages\":" + arr + "," +
                    "\"stream\":false" +
                    "}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(new URI("http://localhost:11434/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            String content = extractAndUnescapeContent(resp.body());
            if (content != null) {
                return content.replace("<think>", "")
                        .replace("</think>", "")
                        .trim();
            }
            return resp.body();
        } catch (Exception e) {
            System.err.println("Cannot contact LLM: " + e.getMessage());
            return null;
        }
    }
}
