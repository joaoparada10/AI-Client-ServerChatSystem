import java.time.Duration;
import java.time.Instant;

public class Session {
    public final String username;
    public volatile String roomName;
    public volatile ClientHandler handler;
    private final Instant createdAt;
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    public Session(String username, ClientHandler handler) {
        this.username = username;
        this.handler = handler;
        this.createdAt = Instant.now();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(createdAt.plus(TOKEN_TTL));
    }

    public void setHandler(ClientHandler handler) {
        this.handler = handler;
    }
}
