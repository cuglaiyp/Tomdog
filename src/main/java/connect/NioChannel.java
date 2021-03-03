package connect;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class NioChannel {
    private SocketChannel socketChannel;
    private ByteBuffer buffer;
    private NioEndpoint.Poller poller;

    public NioChannel(SocketChannel socketChannel, ByteBuffer buffer) {
        this.socketChannel = socketChannel;
        this.buffer = buffer;
    }

    public void setPoller(NioEndpoint.Poller poller) {
        this.poller = poller;
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    public NioEndpoint.Poller getPoller() {
        return poller;
    }
}
