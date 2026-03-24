package observer;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import java.util.logging.Logger;

/**
 * Application entry point — starts the gRPC server hosting SpreadService and NewsService.
 *
 * Architecture:
 *   SpreadService  — external sources register & publish news
 *   NewsService    — external clients subscribe and receive a live stream
 *   MainSpreader   — internal Observer hub that fans out to all registered NewsObservers
 *   NewsServiceImpl implements NewsObserver, so every gRPC client gets the update
 */
public class ServerMain {

    private static final Logger logger = Logger.getLogger(ServerMain.class.getName());
    private static final int PORT = 8080;

    public static void main(String[] args) throws Exception {

        // Core domain object
        MainSpreader mainSpreader = new MainSpreader();

        // gRPC service for receivers — also acts as a NewsObserver
        NewsServiceImpl newsService = new NewsServiceImpl();

        // Wire: when mainSpreader broadcasts, newsService streams to gRPC clients
        mainSpreader.registerObserver(newsService);

        // Build and start the server
        Server server = ServerBuilder.forPort(PORT)
                .addService(new SpreadServiceImpl(mainSpreader))
                .addService(newsService)
                .addService(ProtoReflectionService.newInstance()) // enables grpcurl introspection
                .build()
                .start();

        logger.info("gRPC News Broadcasting server started on port " + PORT);

        // Graceful shutdown on JVM exit (Ctrl+C / SIGTERM)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received — stopping server...");
            newsService.closeAllClients();
            server.shutdown();
            logger.info("Server stopped.");
        }, "grpc-shutdown-hook"));

        server.awaitTermination();
    }
}

