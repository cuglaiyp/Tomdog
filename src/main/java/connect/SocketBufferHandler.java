package connect;

import java.nio.ByteBuffer;

/**
 * 由这个类来管理输入输出buffer。只管理，与channel打交道由NioChannelWrapper来执行。
 */
public class SocketBufferHandler {
    private volatile ByteBuffer readBuffer;
    private volatile ByteBuffer writeBuffer;
    private boolean readBufferConfiguredForWrite = true;
    private boolean writeBufferConfiguredForWrite = true;

    public SocketBufferHandler(int readBufferSize, int writeBufferSize) {
        this.readBuffer = ByteBuffer.allocate(readBufferSize);
        this.writeBuffer = ByteBuffer.allocate(writeBufferSize);
    }

    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }

    public ByteBuffer getWriteBuffer() {
        return writeBuffer;
    }



    // TODO 因为不管是readBuffer还是writeBuffer都有两种模式：读、写模式。所以我们还需要两个字段分别标识readBuffer和writeBuffer目前的模式，两个方法分别切换两个buffer的模式
    // 终于到这个方法了来了
    public void configureReadBufferForRead() {
        setReadBufferConfiguredForWrite(false);
    }

    public void configureReadBufferForWrite() {
        setReadBufferConfiguredForWrite(true);
    }

    /**
     * 这个方法很简单的。 写 -> 读：直接flip    读 -> 写：把数据往最左边移
     * @param readBufferConFiguredForWrite false标识读模式 true标识写模式
     */
    private void setReadBufferConfiguredForWrite(boolean readBufferConFiguredForWrite) {
        // 判断状态否需要改变
        if (this.readBufferConfiguredForWrite != readBufferConFiguredForWrite) {
            if (readBufferConFiguredForWrite) {
                // 切换写模式
                int remaining = readBuffer.remaining();
                if (remaining == 0) {
                    readBuffer.clear();
                } else {
                    readBuffer.compact();
                }
            } else {
                // 切换读模式
                readBuffer.flip();
            }
            this.readBufferConfiguredForWrite = readBufferConFiguredForWrite;
        }
    }
}
