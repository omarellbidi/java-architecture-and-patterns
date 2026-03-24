package observer;

import io.grpc.stub.StreamObserver;
import observer.SpreadServiceOuterClass.NewsUpdate;
import observer.SpreadServiceOuterClass.ReceiverRequest;

import java.time.LocalDateTime;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implements the gRPC NewsService.
 * Also implements NewsObserver so it can be directly registered with MainSpreader,
 * bridging the internal Observer pattern to external gRPC streaming clients.
 */
public class NewsServiceImpl extends NewsServiceGrpc.NewsServiceImplBase implements NewsObserver {

    private static final Logger logger = Logger.getLogger(NewsServiceImpl.class.getName());

    /** Thread-safe list of connected gRPC streaming clients. */
    private final CopyOnWriteArrayList<StreamObserver<NewsUpdate>> clients = new CopyOnWriteArrayList<>();

    @Override
    public void registerReceiver(ReceiverRequest request, StreamObserver<NewsUpdate> responseObserver) {
        clients.add(responseObserver);
        logger.info("New gRPC client registered: " + request.getClientId()
                    + " (total clients: " + clients.size() + ")");

        // Send an immediate acknowledgement
        responseObserver.onNext(NewsUpdate.newBuilder()
                .setNews("Successfully subscribed to news feed")
                .setSource("Server")
                .setTimestamp(LocalDateTime.now().toString())
                .build());

        // Remove the client when the stream is cancelled or closed
        // (grpc-java calls onError when the client disconnects)
    }

    /**
     * Called by MainSpreader via the NewsObserver interface.
     * Broadcasts the news to every connected gRPC client.
     * Clients that have disconnected are removed from the list.
     */
    @Override
    public void update(String news, String source, LocalDateTime timestamp) {
        broadcastToClients(news, source, timestamp);
    }

    /**
     * Pushes a news update to all registered gRPC clients.
     * Clients that fail to receive (disconnected) are automatically removed.
     */
    public void broadcastToClients(String news, String source, LocalDateTime timestamp) {
        NewsUpdate update = NewsUpdate.newBuilder()
                .setNews(news)
                .setSource(source)
                .setTimestamp(timestamp.toString())
                .build();

        for (StreamObserver<NewsUpdate> client : clients) {
            try {
                client.onNext(update);
            } catch (Exception e) {
                // Client has disconnected — remove it
                logger.log(Level.WARNING, "Removing disconnected client: " + e.getMessage());
                clients.remove(client);
            }
        }
    }

    /** Gracefully closes all open client streams (e.g. on server shutdown). */
    public void closeAllClients() {
        clients.forEach(client -> {
            try { client.onCompleted(); } catch (Exception ignored) {}
        });
        clients.clear();
    }
}

