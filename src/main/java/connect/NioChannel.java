package connect;

import java.nio.channels.SocketChannel;

public class NioChannel {
    private SocketChannel socketChannel;
    private SocketBufferHandler socketBufferHandler;
    private NioEndpoint.Poller poller;

    public NioChannel(SocketChannel socketChannel, SocketBufferHandler socketBufferHandler) {
        this.socketChannel = socketChannel;
        this.socketBufferHandler = socketBufferHandler;
    }

    public void setPoller(NioEndpoint.Poller poller) {
        this.poller = poller;
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public SocketBufferHandler getSocketBufferHandler() {
        return socketBufferHandler;
    }

    public NioEndpoint.Poller getPoller() {
        return poller;
    }
}
