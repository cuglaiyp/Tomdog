package connect;

import javax.annotation.Resource;
import java.nio.Buffer;

public class Http11Processor {

    private final CoyoteAdapter adapter;
    private final Request request;
    private final Response response;
    private final Http11NioProtocol protocol;
    private volatile NioEndpoint.NioChannelWrapper nioChannelWrapper;
    private final Http11InputBuffer inputBuffer;
    private final Http11OutputBuffer outputBuffer;

    public Http11Processor(Http11NioProtocol protocol, CoyoteAdapter adapter) {
        this(protocol, adapter, new Request(), new Response());
    }

    public Http11Processor(Http11NioProtocol protocol, CoyoteAdapter adapter, Request request, Response response) {
        this.protocol = protocol;
        this.adapter = adapter;
        this.request = request;
        this.response = response;

        this.inputBuffer = new Http11InputBuffer(request, 8*1024, true);
        this.request.setInputBuffer(this.inputBuffer);
        this.outputBuffer = new Http11OutputBuffer(response, 8*1024);
        this.response.setOutputBuffer(this.outputBuffer);
    }

    public void process(NioEndpoint.NioChannelWrapper nioChannelWrapper) {
        service(nioChannelWrapper);
    }

    public void service(NioEndpoint.NioChannelWrapper nioChannelWrapper) {
        //request.setStage(); // 省略掉"设置解析阶段"的代码
        setNioChannelWrapper(nioChannelWrapper);
        inputBuffer.init(nioChannelWrapper);
        outputBuffer.init(nioChannelWrapper);
        while (true) {
            if (inputBuffer.parseRequestLine()){

            }
        }

    }


    public CoyoteAdapter getAdapter() {
        return adapter;
    }

    public Request getRequest() {
        return request;
    }

    public Response getResponse() {
        return response;
    }

    public Http11NioProtocol getProtocol() {
        return protocol;
    }

    public NioEndpoint.NioChannelWrapper getNioChannelWrapper() {
        return nioChannelWrapper;
    }

    public void setNioChannelWrapper(NioEndpoint.NioChannelWrapper nioChannelWrapper) {
        this.nioChannelWrapper = nioChannelWrapper;
    }
}
