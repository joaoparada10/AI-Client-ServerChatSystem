import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class ChatRoom {
    private final String name;
    private final boolean ai;
    private final List<ClientHandler> clients = new ArrayList<>();
    private final List<Message> history = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    public ChatRoom(String n, boolean isAI) {
        name = n;
        ai = isAI;
    }

    public boolean isAIRoom() {
        return ai;
    }

    public void addClient(ClientHandler c) {
        lock.lock();
        try {
            clients.add(c);
            broadcastAll("[" + c.getUsername() + " enters the room]");
        } finally {
            lock.unlock();
        }
    }

    public void removeClient(ClientHandler c) {
        lock.lock();
        try {
            clients.remove(c);
            broadcastAll("[" + c.getUsername() + " leaves the room]");
        } finally {
            lock.unlock();
        }
    }

    public void broadcastAll(String msg) {
        lock.lock();
        try {
            for (var c : clients)
                c.sendMessage(msg);
        } finally {
            lock.unlock();
        }
    }

    public void userMessage(String userMsg, ClientHandler from) {
        broadcastAll(userMsg);
        if (ai) {
            lock.lock();
            try {
                history.add(new Message("user", userMsg));
                String bot = ChatServer.getAIChatResponse(history);
                if (bot != null) {
                    history.add(new Message("assistant", bot));
                    broadcastAll("Bot: " + bot);
                } else {
                    broadcastAll("Bot: [No response available]");
                }
            } finally {
                lock.unlock();
            }
        }
    }

}
