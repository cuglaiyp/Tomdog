package boot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class Client {
    public static void main(String[] args) throws IOException {
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.connect(new InetSocketAddress("127.0.0.1", 8080));
        ByteBuffer byteBuffer = ByteBuffer.wrap("hello server".getBytes());
        socketChannel.write(byteBuffer);
        //socketChannel.close();
        while (true);
    }
}
