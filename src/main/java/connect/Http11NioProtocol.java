package connect;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议处理器，用来统筹连接处理
 *
 * 在新建连接器的时候，会根据参数（HTTP/1.1）选择创建创建这个类
 */
public class Http11NioProtocol {
    private NioEndpoint endpoint;
    private ConnectionHandler connectionHandler;
    private CoyoteAdapter adapter;

    public Http11NioProtocol() throws IOException {
        this.endpoint = new NioEndpoint();
        this.connectionHandler = new ConnectionHandler(this);
    }

    private Http11Processor createProcessor() {
        return new Http11Processor(this, adapter);
    }

    /**
     * 这个类维护了nioChannel与Processor的映射关系。 看了一下代码，并不知道为什么要维护这个映射关系
     */
    public static class ConnectionHandler {
        private final Map<NioChannel, Http11Processor> connections = new ConcurrentHashMap<>();
        private Http11NioProtocol protocol;

        public ConnectionHandler(Http11NioProtocol http11NioProtocol) {
            this.protocol = http11NioProtocol;
        }

        public void process(NioEndpoint.NioChannelWrapper nioChannelWrapper) {
            NioChannel nioChannel = nioChannelWrapper.getNioChannel();
            Http11Processor processor = connections.get(nioChannel);
            if (processor == null) {
                processor = getProtocol().createProcessor();
                // TODO 这个注册方法稍后在讨论 目前不是很懂
                // register(processor);
            }
            // 关联这俩
            connections.put(nioChannel, processor);
            processor.process(nioChannelWrapper);
        }

        private void register(Http11Processor processor) {

        }

        private Http11NioProtocol getProtocol() {
            return this.protocol;
        }
    }

    

}
