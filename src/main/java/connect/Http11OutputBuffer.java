package connect;

public class Http11OutputBuffer {
    private final Response response;
    private final int headerBufferSize;
    private NioEndpoint.NioChannelWrapper nioChannelWrapper;

    public Http11OutputBuffer(Response response, int headerBufferSize) {
        this.response = response;
        this.headerBufferSize = headerBufferSize;
    }

    public void init(NioEndpoint.NioChannelWrapper nioChannelWrapper) {
        this.nioChannelWrapper = nioChannelWrapper;
    }

    public Response getResponse() {
        return response;
    }

    public int getHeaderBufferSize() {
        return headerBufferSize;
    }
}
