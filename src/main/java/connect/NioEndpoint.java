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
        public int read(boolean block, ByteBuffer byteBuffer) {
            int nRead = populateReadBuffer(byteBuffer);
            return 0;
        }

        private int populateReadBuffer(ByteBuffer byteBuffer) {
            SocketBufferHandler socketBufferHandler = nioChannel.getSocketBufferHandler();
            // 首先要将socketBufferHandler中的readBuffer切换成读模式
            socketBufferHandler.configureReadBufferForRead();

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
