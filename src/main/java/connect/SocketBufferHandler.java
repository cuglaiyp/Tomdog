package connect;

import java.nio.ByteBuffer;

/**
 * 由这个类来管理socket中的数据和buffer
 */
public class SocketBufferHandler {
    private volatile ByteBuffer readBuffer;
    private volatile ByteBuffer writeBuffer;

    public SocketBufferHandler(int readBufferSize, int writeBufferSize) {
        this.readBuffer = ByteBuffer.allocate(readBufferSize);
        this.writeBuffer = ByteBuffer.allocate(writeBufferSize);
    }
}
