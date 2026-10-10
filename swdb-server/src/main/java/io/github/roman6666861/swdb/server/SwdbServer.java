package io.github.roman6666861.swdb.server;

import io.github.roman6666861.swdb.server.pgwire.PgConnectionHandler;
import io.github.roman6666861.swdb.server.pgwire.PgMessageDecoder;
import io.github.roman6666861.swdb.sql.SqlEngine;
import io.github.roman6666861.swdb.storage.KvStore;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Сервер СУБД: принимает подключения по протоколу PostgreSQL. Сетевой ввод-вывод
 * обрабатывают event loop'ы Netty, а запросы выполняются на виртуальных потоках.
 */
public final class SwdbServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SwdbServer.class);

    private final KvStore store;
    private final ExecutorService queryExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    private final Channel serverChannel;

    private SwdbServer(ServerConfig config) throws InterruptedException {
        store = KvStore.open(config.dataDir());
        SqlEngine engine = new SqlEngine();
        serverChannel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new PgMessageDecoder(),
                                new PgConnectionHandler(engine, queryExecutor));
                    }
                })
                .bind(config.host(), config.port())
                .sync()
                .channel();
        log.info("Select_Whoops_DB listening on {}, data dir {}",
                serverChannel.localAddress(), config.dataDir().toAbsolutePath());
    }

    public static SwdbServer start(ServerConfig config) throws InterruptedException {
        return new SwdbServer(config);
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** Блокирует поток до остановки сервера. */
    public void awaitTermination() throws InterruptedException {
        serverChannel.closeFuture().sync();
    }

    @Override
    public void close() {
        log.info("Shutting down");
        serverChannel.close().syncUninterruptibly();
        bossGroup.shutdownGracefully().syncUninterruptibly();
        workerGroup.shutdownGracefully().syncUninterruptibly();
        queryExecutor.close();
        store.close();
    }
}
