package io.github.jukomu.picacomic.core;

import okhttp3.Dns;
import okhttp3.Protocol;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okhttp3.mockwebserver.MockWebServer;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * 将逻辑 HTTPS 443 endpoint 映射到本地 MockWebServer 非特权端口的测试夹具。
 */
final class LocalTlsFixture implements AutoCloseable {

    final MockWebServer server;
    final HandshakeCertificates clientCertificates;
    final Dns dns = hostname -> List.of(InetAddress.getLoopbackAddress());
    final SocketFactory socketFactory;

    LocalTlsFixture() throws IOException {
        HeldCertificate certificate = new HeldCertificate.Builder()
                .commonName("local-picacomic-fixture")
                .addSubjectAlternativeName("api-one.test")
                .addSubjectAlternativeName("api-two.test")
                .addSubjectAlternativeName("img.picacomic.com")
                .addSubjectAlternativeName("s2.picacomic.com")
                .addSubjectAlternativeName("s3.picacomic.com")
                .addSubjectAlternativeName("storage.picacomic.com")
                .addSubjectAlternativeName("storage1.picacomic.com")
                .addSubjectAlternativeName("storage-b.picacomic.com")
                .build();
        HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
                .heldCertificate(certificate)
                .build();
        this.clientCertificates = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate())
                .build();
        this.server = new MockWebServer();
        this.server.setProtocols(List.of(Protocol.HTTP_1_1));
        this.server.useHttps(serverCertificates.sslSocketFactory(), false);
        this.server.start();
        this.socketFactory = new FixedPortSocketFactory(server.getPort());
    }

    String url(String host, String path) {
        return "https://" + host + path;
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }

    private static final class FixedPortSocketFactory extends SocketFactory {
        private final int targetPort;

        private FixedPortSocketFactory(int targetPort) {
            this.targetPort = targetPort;
        }

        private Socket create() {
            return new RedirectingSocket(targetPort);
        }

        @Override
        public Socket createSocket() throws IOException {
            return create();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return create();
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return create();
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return create();
        }

        @Override
        public Socket createSocket(InetAddress address, int port,
                                   InetAddress localAddress, int localPort) throws IOException {
            return create();
        }

        private static final class RedirectingSocket extends Socket {
            private final int targetPort;

            private RedirectingSocket(int targetPort) {
                this.targetPort = targetPort;
            }

            @Override
            public void connect(java.net.SocketAddress endpoint, int timeout) throws IOException {
                super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), timeout);
            }
        }
    }
}
