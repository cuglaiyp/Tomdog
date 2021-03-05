package connect;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public class Acceptor implements Runnable{

    private final NioEndpoint endpoint;

    public Acceptor(NioEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public void run() {
        SocketChannel socketChannel = null;
        while (true){
            try {
                // Acceptor使用endpoint来获取连接，实际上就是endpoint的serverSocketChannel.accept();
                socketChannel = endpoint.serverSocketAccept();
            } catch (IOException e) {
                e.printStackTrace();
            }
            // 继续调用endpoint处理连接
            endpoint.setSocketChannelOptions(socketChannel);
        }
    }
}
