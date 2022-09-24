package ok.dht.test.shashulovskiy;

import jdk.incubator.foreign.MemorySegment;
import ok.dht.Service;
import ok.dht.ServiceConfig;
import ok.dht.test.ServiceFactory;
import ok.dht.test.shashulovskiy.dao.BaseEntry;
import ok.dht.test.shashulovskiy.dao.Config;
import ok.dht.test.shashulovskiy.dao.Dao;
import ok.dht.test.shashulovskiy.dao.Entry;
import ok.dht.test.shashulovskiy.dao.drozdov.MemorySegmentDao;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.server.AcceptorConfig;
import one.nio.server.SelectorThread;
import one.nio.util.Utf8;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    private static final long THRESHOLD_BYTES = 1 << 29;

    private final ServiceConfig config;
    private HttpServer server;

    private Dao<MemorySegment, Entry<MemorySegment>> dao;

    public ServiceImpl(ServiceConfig config) throws IOException {
        this.config = config;
    }

    @Override
    public CompletableFuture<?> start() throws IOException {
        this.dao = new MemorySegmentDao(new Config(config.workingDir(), THRESHOLD_BYTES));

        server = new HttpServer(createConfigFromPort(config.selfPort())) {
            @Override
            public void handleDefault(Request request, HttpSession session) throws IOException {
                Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
                session.sendResponse(response);
            }

            @Override
            public synchronized void stop() {
                for (SelectorThread selector : selectors) {
                    selector.selector.forEach(Session::close);
                }

                super.stop();
            }
        };
        server.start();
        server.addRequestHandlers(this);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> stop() throws IOException {
        server.stop();
        return CompletableFuture.completedFuture(null);
    }

    @Path("/v0/entity")
    public Response handle(Request request) throws IOException {
        String id = request.getParameter("id=");
        if (id == null) {
            return new Response(
                    Response.BAD_REQUEST,
                    Utf8.toBytes("No id provided")
            );
        } else if (id.equals("")) {
            return new Response(
                    Response.BAD_REQUEST,
                    Utf8.toBytes("Empty id")
            );
        }

        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                Entry<MemorySegment> memorySegmentEntry = dao.get(stringToMemorySegment(id));
                if (memorySegmentEntry == null) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                } else {
                    return new Response(Response.OK, memorySegmentEntry.value().toByteArray());
                }
            }
            case Request.METHOD_PUT -> {
                dao.upsert(
                        new BaseEntry<>(
                                stringToMemorySegment(id),
                                MemorySegment.ofArray(request.getBody())
                        )
                );

                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                dao.upsert(
                        new BaseEntry<>(
                                stringToMemorySegment(id),
                                null
                        )
                );

                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    private static MemorySegment stringToMemorySegment(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpServerConfig createConfigFromPort(int port) {
        HttpServerConfig httpConfig = new HttpServerConfig();
        AcceptorConfig acceptor = new AcceptorConfig();
        acceptor.port = port;
        acceptor.reusePort = true;
        httpConfig.acceptors = new AcceptorConfig[]{acceptor};
        return httpConfig;
    }

    @ServiceFactory(stage = 1, week = 1)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            try {
                return new ServiceImpl(config);
            } catch (IOException e) {
                return null;
            }
        }
    }
}