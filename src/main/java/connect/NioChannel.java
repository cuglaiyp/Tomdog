package connect;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class NioChannel {
    private SocketChannel socketChannel;
    private SocketBufferHandler socketBufferHandler;
    private NioEndpoint.Poller poller;
    private NioEndpoint.NioChannelWrapper nioChannelWrapper;

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

    public void setNioChannelWrapper(NioEndpoint.NioChannelWrapper nioChannelWrapper) {
        this.nioChannelWrapper = nioChannelWrapper;
    }

    public int read(ByteBuffer dst) throws IOException {
        return socketChannel.read(dst);
    }
}
