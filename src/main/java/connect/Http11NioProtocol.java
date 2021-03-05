package connect;

import java.io.IOException;

/**
 * 协议处理器，用来统筹连接处理
 *
 * 在新建连接器的时候，会根据参数（HTTP/1.1）选择创建创建这个类
 */
public class Http11NioProtocol {
    private NioEndpoint endpoint;
    private ConnectionHandler connectionHandler;

    public Http11NioProtocol() throws IOException {
        this.endpoint = new NioEndpoint();
        this.connectionHandler = new ConnectionHandler(this);

    }
}
