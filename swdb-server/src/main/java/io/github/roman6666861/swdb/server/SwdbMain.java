package io.github.roman6666861.swdb.server;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Точка входа: {@code swdb --port 5432 --data-dir data}.
 */
@Command(name = "swdb", mixinStandardHelpOptions = true, description = "Select_Whoops_DB server")
public final class SwdbMain implements Callable<Integer> {

    @Option(names = "--host", defaultValue = "127.0.0.1", description = "Listen address (default: ${DEFAULT-VALUE})")
    private String host;

    @Option(names = "--port", defaultValue = "5432", description = "Listen port (default: ${DEFAULT-VALUE})")
    private int port;

    @Option(names = "--data-dir", defaultValue = "data", description = "Data directory (default: ${DEFAULT-VALUE})")
    private Path dataDir;

    @Override
    public Integer call() throws InterruptedException {
        SwdbServer server = SwdbServer.start(new ServerConfig(host, port, dataDir));
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(server::close));
        server.awaitTermination();
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new SwdbMain()).execute(args));
    }
}
