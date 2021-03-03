package connect;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class NioEndpoint implements Endpoint {

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


    @Override
    public SocketChannel serverSocketAccept() throws IOException {
        return serverSocketChannel.accept();
    }

    @Override
    public boolean setSocketChannelOptions(SocketChannel socketChannel) {
        try {
            // 将socketChannel设置为非阻塞模式
            socketChannel.configureBlocking(false);
            //TODO 配置socket属性
            //Socket socket = socketChannel.socket();
            // 将socketChannel连同它需要的Buffer包装成Niochannel
            NioChannel nioChannel = new NioChannel(socketChannel, ByteBuffer.allocate(8 * 1024));
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
         * 将连接注册进poller中，由poller线程进行处理
         *
         * @param nioChannel 包装好的socketChannel和Buffer
         */
        public void register(NioChannel nioChannel) {
            nioChannel.setPoller(this);
            // 继续将nioChannel包装成为poller event,交给poller线程进行处理
            PollerEvent pollerEvent = new PollerEvent(nioChannel);
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
                    NioChannel nioChannel = (NioChannel) selectionKey.attachment();
                    processKey(selectionKey, nioChannel);
                }
            }
        }

        private void processKey(SelectionKey selectionKey, NioChannel nioChannel) {
            if (selectionKey.isReadable()) {
                unreg(selectionKey, selectionKey.readyOps());
                getExecutor().execute(new SocketProcessorTask(nioChannel));
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

    public static class PollerEvent implements Runnable {

        private NioChannel nioChannel;

        public PollerEvent(NioChannel nioChannel) {
            this.nioChannel = nioChannel;
        }

        // 注册感兴趣的事件
        @Override
        public void run() {
            try {
                nioChannel.getSocketChannel().register(nioChannel.getPoller().getSelector(), SelectionKey.OP_READ, nioChannel);
            } catch (ClosedChannelException e) {
                e.printStackTrace();
            }
        }
    }

    public static class SocketProcessorTask implements Runnable {
        private NioChannel nioChannel;

        public SocketProcessorTask(NioChannel nioChannel) {
            this.nioChannel = nioChannel;
        }

        @Override
        public void run() {
            SelectionKey selectionKey = nioChannel.getSocketChannel().keyFor(nioChannel.getPoller().getSelector());
            SocketChannel socketChannel = nioChannel.getSocketChannel();
            try {
                ByteBuffer byteBuffer = nioChannel.getBuffer();
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
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }
}
