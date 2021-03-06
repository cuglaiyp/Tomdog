package connect;

import org.omg.IOP.TAG_JAVA_CODEBASE;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class NioEndpoint{

    public static final int OP_REGISTER = 0x100;


    public NioEndpoint() throws IOException {
        this.serverSocketChannel = ServerSocketChannel.open();
        this.serverSocketChannel.socket().bind(new InetSocketAddress(8080));
    }

    private ServerSocketChannel serverSocketChannel;
    private Acceptor[] acceptors;
    private Poller[] pollers;
    private Executor executor;
    private AtomicInteger pollerRotater = new AtomicInteger(0);

    public ServerSocketChannel getServerSocketChannel() {
        return serverSocketChannel;
    }

    public Executor getExecutor() {
        return executor;
    }

    // 随机算法,拿一个poller出来
    private Poller getPoller0() {
        int idx = Math.abs(pollerRotater.incrementAndGet()) % pollers.length;
        return pollers[idx];
    }


    public SocketChannel serverSocketAccept() throws IOException {
        return serverSocketChannel.accept();
    }

    public boolean setSocketChannelOptions(SocketChannel socketChannel) {
        try {
            // 将socketChannel设置为非阻塞模式
            socketChannel.configureBlocking(false);
            //TODO 配置socket属性
            //Socket socket = socketChannel.socket();
            // 将socketChannel连同它需要的Buffer包装成Niochannel
            NioChannel nioChannel = new NioChannel(socketChannel, new SocketBufferHandler(8*1024, 8*1024));
            // 设置好后，将我们的连接交给poller处理
            getPoller0().register(nioChannel);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }


    public void createExecutor() {
        executor = new ThreadPoolExecutor(10, 200, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    public void createPollers() throws IOException {
        pollers = new Poller[2];
        for (int i = 0; i < pollers.length; i++) {
            pollers[i] = new Poller();
            Thread pollerThread = new Thread(pollers[i], "-ClientPoller-" + i);
            pollerThread.setPriority(Thread.NORM_PRIORITY);
            pollerThread.setDaemon(true);
            pollerThread.start();
        }

    }

    private void createAcceptors() {
        acceptors = new Acceptor[1];
        for (int i = 0; i < acceptors.length; i++) {
            acceptors[i] = new Acceptor(this);
            Thread acceptorThread = new Thread(acceptors[i], "-ClientAcceptor-" + i);
            acceptorThread.setPriority(Thread.NORM_PRIORITY);
            acceptorThread.setDaemon(true);
            acceptorThread.start();
        }
    }

    public void start() throws IOException {
        if (executor == null) {
            createExecutor();
        }
        if (pollers == null) {
            createPollers();
        }
        if (acceptors == null) {
            createAcceptors();
        }
    }

    private Http11NioProtocol.ConnectionHandler connectionHandler;
    public Http11NioProtocol.ConnectionHandler getConnectionHandler() {
        return connectionHandler;
    }
    public void setConnectionHandler(Http11NioProtocol.ConnectionHandler connectionHandler) {
        this.connectionHandler = connectionHandler;
    }

    public class Poller implements Runnable {

        private ConcurrentLinkedQueue<PollerEvent> events = new ConcurrentLinkedQueue<>();
        private Selector selector;
        private volatile int keyCount;

        public ConcurrentLinkedQueue<PollerEvent> getEvents() {
            return events;
        }

        public Selector getSelector() {
            return selector;
        }

        public int getKeyCount() {
            return keyCount;
        }

        public Poller() throws IOException {
            this.selector = Selector.open();
        }

        /**
         * 将连接继续包装，并注册进poller中，由poller线程进行处理
         *
         * @param nioChannel 包装好的socketChannel和Buffer
         */
        public void register(final NioChannel nioChannel) {
            nioChannel.setPoller(this);
            NioChannelWrapper nioChannelWrapper = new NioChannelWrapper(nioChannel, NioEndpoint.this);
            nioChannel.setNioChannelWrapper(nioChannelWrapper);
            // 既然nioChannel已经设置了Poller，并且这个wrapper也与nioChannel关联起来了，那么这里又设置一遍Poller是不是有点多余
            nioChannelWrapper.setPoller(this);
            nioChannelWrapper.interestOps(SelectionKey.OP_READ);
            /*
             * 这里省略一系列的nioChannelWrapper属性设置。比如：readTimeout、writeTimeout、keepAlive等等
             */
            // 继续将nioChannel包装成为poller event,交给poller线程进行处理。因为是新进来的连接，所以置标志位为这个，在后面的处理会用到
            PollerEvent pollerEvent = new PollerEvent(nioChannelWrapper, OP_REGISTER);
            addEvent(pollerEvent);

        }

        private void addEvent(PollerEvent pollerEvent) {
            events.offer(pollerEvent);
            // TODO 这里可以设置标志位因为又添加了事件，所以可以设置一个wakeupCounter，用这个唤醒poller，进行selectNow。
        }

        @Override
        public void run() {
            while (true) {
                exeEvents();
                try {
                    // 这里用select行不通，会一直阻塞。为什么呢？
                    // 因为：当我们有一个新的连接进来了，这时触发的接收就绪（SelectionKey.OP_ACCEPT）事件。但是因为我们没有把ServerSocketChannel注册到Selector上，所以这里不会被唤醒
                    // keyCount = selector.select();

                    // 经过一天多的排查，问题终于找到了
                    // 因为是将任务交给线程池在执行，这样就产生了异步，失去同步之后，线程池执行任务的时间是不确定的。
                    // 只要线程池还没有执行任务，也就说还没有从通道中读取我们的数据，那么selector就会不断触发读就绪事件（水平触发原则）
                    // 所以我们这里我们会有不定次数能够select到。这也是为什么执行的时候会出现这种问题，而调试的时候没有（调试执行时间慢，线程池早就读取完数据了）
                    // 了解一下tomcat是怎么解决这个问题的:
                    //      tomcat使用了unreg方法来解决这个问题，就是将该key准备好的事件取反，取消掉，这样就不会一直触发了
                    keyCount = selector.selectNow();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (keyCount == 0) continue;
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator != null && iterator.hasNext()) {
                    SelectionKey selectionKey = iterator.next();
                    iterator.remove();
                    NioChannelWrapper nioChannelWrapper = (NioChannelWrapper) selectionKey.attachment();
                    processKey(selectionKey,  nioChannelWrapper);
                }
            }
        }

        private void processKey(SelectionKey selectionKey, NioChannelWrapper nioChannelWrapper) {
            if (selectionKey.isReadable()) {
                unreg(selectionKey, selectionKey.readyOps());
                getExecutor().execute(new SocketProcessor(nioChannelWrapper));
            }

        }

        private void unreg(SelectionKey selectionKey, int readyOps) {
            selectionKey.interestOps(selectionKey.interestOps() & ~readyOps);
        }

        private boolean exeEvents() {
            boolean hasResult = false;
            PollerEvent pe = null;
            for (int i = 0, size = events.size(); (i < size) && (pe = events.poll()) != null; i++) {
                hasResult = true;
                // 这个方法里面要注册感兴趣的事件
                pe.run();

            }
            return hasResult;
        }

    }

    /**
     * 将NioChannel再包装一遍，多了一个Endpoint。具体这个类干什么的我也不太清楚，先往后面写吧。
     *
     * 有点理解为什么会有很多冗余的字段了。可能是因为泛型的原因。
     * 你比如这个类里面，nioChannel是泛型类型，那么在这个类内部想用nioChannel拿到nioChannel中的SocketBufferHandler就没有办法。
     * 所以这个类需要再额外保存一下nioChannel中的SocketBufferHandler（理解错误）
     */
    public static class NioChannelWrapper{
        private final NioChannel nioChannel;
        private final NioEndpoint endpoint;
        private Poller poller;
        private int interestOps;
        public NioChannelWrapper(NioChannel nioChannel, NioEndpoint nioEndpoint) {
            this.nioChannel = nioChannel;
            this.endpoint = nioEndpoint;
        }

        // 不是很懂为什么这个方法是放在这个类中。
        // TODO read方法 将SocketBufferHandler中readBuffer中的数据读取到我们指定的byte数组中，如果readBuffer中没有数据，那么就去socket中去读取数据。
        // 这个方法终于到来了

        /**
         * 将channel中的数据读到byteBuffer中
         * @param block
         * @param to 目标buffer
         * @return nRead表示有多少数据填充到了to里面
         */
        public int read(boolean block, ByteBuffer to) throws IOException {
            int nRead = populateReadBuffer(to);
            // 如果转移了数据，那么就返回转移的量
            if(nRead > 0) return nRead;
            // 没有返回说明没有转移数据，两个原因：
            //      1.SocketBufferHandler中的readBuffer没有数据，那么就需要去channel中拿
            //      2.to中的数据是满的，没有容量
            SocketBufferHandler socketBufferHandler = nioChannel.getSocketBufferHandler();
            int limit = socketBufferHandler.getReadBuffer().capacity();
            // 如果to的剩余空间大于readBuffer的总空间。说明两个问题
            //      1.to中的数据不是满的，所以前面没有返回的原因是因为1.readBuffer中没有数据
            //      2.to的剩余容量比readBuffer总空间还要大，那么就没有必要从channel中读数据到readBuffer再转移到to中。直接从channel到to可以一次性拿。
            if (to.remaining() >= limit){
                // 本来to的limit等于它的capacity，position等于它的limit。为写模式。
                // 为什么要把可写容量缩小到与readBuffer等大呢，为什么不直接最大限度的读取呢？
                to.limit(to.position() + limit);
                nRead = fillReadBuffer(block, to);
            }else { // to的剩余空间小于readBuffer的总空间，那么就往readBuffer里面放，再转到to里面
                nRead = fillReadBuffer(block);
                if (nRead > 0){
                    nRead = populateReadBuffer(to);
                }

            }
            return nRead;
        }

        private int fillReadBuffer(boolean block) throws IOException {
            getNioChannel().getSocketBufferHandler().configureReadBufferForWrite();
            return fillReadBuffer(block, getNioChannel().getSocketBufferHandler().getReadBuffer());
        }

        private int fillReadBuffer(boolean block, ByteBuffer to) throws IOException {
            int nRead = 0;
            NioChannel nioChannel =  getNioChannel();
            if (block){
                //TODO 先省略掉阻塞逻辑
            }else {
                // read方法会从buffer的position处开始往buffer中填数据直到position的值等于limit
                nRead = nioChannel.read(to);
            }
            return nRead;
        }


        /**
         * 就封装了一下transfer方法
         * @param byteBuffer
         * @return
         */
        private int populateReadBuffer(ByteBuffer byteBuffer) {
            SocketBufferHandler socketBufferHandler = nioChannel.getSocketBufferHandler();
            // 首先要将socketBufferHandler中的readBuffer切换成读模式
            socketBufferHandler.configureReadBufferForRead();
            int nRead = transfer(socketBufferHandler.getReadBuffer(), byteBuffer);
            return nRead;
        }

        /**
         * 将from中的数据最大限度的转移到to中
         * @param from
         * @param to
         * @return 转移数据量的大小
         */
        private int transfer(ByteBuffer from, ByteBuffer to) {
            // 这个时候to是写模式，remaining肯定是特别大的；from是读模式，remaining比较小。所以max一定是取到from的值
            // 什么情况会取到to的remaining呢？那就是当to中还没有使用的数据特别多的时候，这个时候remaining就会特别小，可能会小于from。（to的remaining表示to剩余空间）
            // 我们把这两种情况分别讨论一下
            // 1.如果取到的是from的remaining，说明from里面可取的数据小于to的可存放空间，那么可以将所有的这些数据（remaining = from.position() + max）从from转移到to
            // 2.如果取到的是to的remaining，说明from里面可取得数据大于to可存放的空间，那么就只能取to剩余空间这么大的数据（from.position() + max）从from转移到to
            int max = Math.min(from.remaining(), to.remaining());
            // 如果from有数据，那么把这些数据转移到to里面去
            if (max > 0) {
                // 这个地方的逻辑就是在控制从from到底是取所有数据还是to剩余容量的数据。只能说这代码真够优雅，牛！
                int fromLimit = from.limit();
                from.limit(from.position() + max);
                to.put(from);
                from.limit(fromLimit);
            }
            return max;
        }

        public void setPoller(Poller poller) {
            this.poller = poller;
        }

        public int interestOps(int interestOps) {
            this.interestOps = interestOps;
            return this.interestOps;
        }

        public int getInterestOps(){
            return this.interestOps;
        }

        public NioChannel getNioChannel() {
            return nioChannel;
        }

        public NioEndpoint getEndpoint() {
            return endpoint;
        }

        public Poller getPoller() {
            return poller;
        }
    }


    // ---------------------------------------------------------PollerEvent--------------------------------------------------------
    /**
     * 这个类主要是用来将我们新进来的socketChannel注册进selector中
     * 这个类的对象是可以放在集合中复用的对象
     */
    public static class PollerEvent implements Runnable {
        private NioChannelWrapper nioChannelWrapper;
        private int interestOps;

        public PollerEvent(NioChannelWrapper nioChannelWrapper, int interestOps) {
            this.interestOps = interestOps;
            this.nioChannelWrapper = nioChannelWrapper;
            // TODO 这里可以实现重置逻辑，配合复用功能使用
        }

        // 注册感兴趣的事件
        @Override
        public void run() {
            // 如果是新进来的连接，那么将这个连接的SocketChannel注册到当前Poller的Selelctor上，感兴趣事件为Read事件
            if (interestOps == OP_REGISTER){
                try {
                    NioChannel nioChannel = nioChannelWrapper.nioChannel;
                    nioChannel.getSocketChannel().register(nioChannel.getPoller().getSelector(), SelectionKey.OP_READ, nioChannelWrapper);
                } catch (ClosedChannelException e) {
                    e.printStackTrace();
                }
            }else {
                // 如果不是新进来的连接，那么就是这个连接出了问题，在这里做一些相应的操作
                // TODO 连接出问题后相应的一些操作
            }

        }
    }



    // ----------------------------------------------------------SocketProcessor-----------------------------————--------------------------

    /**
     * 这个类就是我们的任务类，最后放入线程池里面执行的。所以必须要有run方法
     */
    public class SocketProcessor implements Runnable {
        private NioChannelWrapper nioChannelWrapper;

        public SocketProcessor(NioChannelWrapper nioChannelWrapper) {
            this.nioChannelWrapper = nioChannelWrapper;
        }

        @Override
        public void run() {
            NioChannel nioChannel = nioChannelWrapper.getNioChannel();
            SelectionKey selectionKey = nioChannel.getSocketChannel().keyFor(nioChannel.getPoller().getSelector());

            // 这里会进行TLS（Https安全方面的）三次握手的判断。我们这里略去。

            try {

                getConnectionHandler().process(nioChannelWrapper);








                /*ByteBuffer byteBuffer = nioChannel.getSocketBufferHandler().getReadBuffer();
                int num = socketChannel.read(byteBuffer);
                if (num > 0) {
                    System.out.println(new String(byteBuffer.array()));
                    String errorMsg = "HTTP/1.1 404 File Not Found\r\n" +
                            "Content-Type: text/html\r\n" +
                            "Content-Length: 23\r\n" +
                            "\r\n" +
                            "<h1>File Not Found</h1>";
                    socketChannel.write(ByteBuffer.wrap(errorMsg.getBytes()));
                } else if (num == -1) {
                    socketChannel.close();
                }*/
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }
}
