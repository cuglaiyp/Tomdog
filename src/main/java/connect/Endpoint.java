package connect;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public interface Endpoint {
    SocketChannel serverSocketAccept() throws IOException;
    boolean setSocketChannelOptions(SocketChannel socketChannel);
}
