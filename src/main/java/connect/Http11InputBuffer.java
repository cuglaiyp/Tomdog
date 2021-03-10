package connect;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class Http11InputBuffer {

    private final Request request;
    private final int headerBufferSize;
    private final boolean rejectIllegalHeaderName;
    private NioEndpoint.NioChannelWrapper nioChannelWrapper;

    private ByteBuffer byteBuffer;
    private boolean parsingRequestLine;
    private int parsingRequestLinePhase = 0;
    private boolean parsingHeader;
    private int end; // 请求头的结束，也是请求体的开始

    public Http11InputBuffer(Request request, int headerBufferSize, boolean rejectIllegalHeaderName) {
        this.request = request;
        this.headerBufferSize = headerBufferSize;
        this.rejectIllegalHeaderName = rejectIllegalHeaderName;
        this.parsingHeader = true;
    }

    public void init(NioEndpoint.NioChannelWrapper nioChannelWrapper) {
        this.nioChannelWrapper = nioChannelWrapper;
        //TODO AppReadBufferHandler不知道什么用，先放着
        int bufLength = headerBufferSize + nioChannelWrapper.getNioChannel().getSocketBufferHandler().getReadBuffer().capacity();
        if (byteBuffer == null || byteBuffer.capacity() < bufLength) {
            byteBuffer = ByteBuffer.allocate(bufLength);
            byteBuffer.position(0).limit(0);
        }
    }

    /**
     *     通过字节解析，代码的复杂程度远超转换成String解析。可能是因为字节解析效率较高吧。
     */
    public boolean parseRequestLine() {
        // 检查状态
        if (!parsingRequestLine) {
            return true;
        }
        // 跳过空行
        if (parsingRequestLinePhase < 2) {
            byte chr = 0;
            do {
                // 如果byteBuffer中的数据已经读取完了或者还没有读取果数据，那么要调用fill方法重新填充
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) {
                        // A read is pending, so no longer in initial state
                        parsingRequestLinePhase = 1;
                        return false;
                    }
                    // At least one byte of the request has been received.
                    // Switch to the socket timeout.
                    wrapper.setReadTimeout(connectionTimeout);
                }
                if (!keptAlive && byteBuffer.position() == 0 && byteBuffer.limit() >= CLIENT_PREFACE_START.length - 1) {
                    boolean prefaceMatch = true;
                    for (int i = 0; i < CLIENT_PREFACE_START.length && prefaceMatch; i++) {
                        if (CLIENT_PREFACE_START[i] != byteBuffer.get(i)) {
                            prefaceMatch = false;
                        }
                    }
                    if (prefaceMatch) {
                        // HTTP/2 preface matched
                        parsingRequestLinePhase = -1;
                        return false;
                    }
                }
                // Set the start time once we start reading data (even if it is
                // just skipping blank lines)
                if (request.getStartTime() < 0) {
                    request.setStartTime(System.currentTimeMillis());
                }
                chr = byteBuffer.get();
            } while ((chr == Constants.CR) || (chr == Constants.LF));
            byteBuffer.position(byteBuffer.position() - 1);

            parsingRequestLineStart = byteBuffer.position();
            parsingRequestLinePhase = 2;
            if (log.isDebugEnabled()) {
                log.debug("Received ["
                        + new String(byteBuffer.array(), byteBuffer.position(), byteBuffer.remaining(), StandardCharsets.ISO_8859_1) + "]");
            }
        }
        if (parsingRequestLinePhase == 2) {
            //
            // Reading the method name
            // Method name is a token
            //
            boolean space = false;
            while (!space) {
                // Read new bytes if needed
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }
                // Spec says method name is a token followed by a single SP but
                // also be tolerant of multiple SP and/or HT.
                int pos = byteBuffer.position();
                byte chr = byteBuffer.get();
                if (chr == Constants.SP || chr == Constants.HT) {
                    space = true;
                    request.method().setBytes(byteBuffer.array(), parsingRequestLineStart,
                            pos - parsingRequestLineStart);
                } else if (!HttpParser.isToken(chr)) {
                    byteBuffer.position(byteBuffer.position() - 1);
                    throw new IllegalArgumentException(sm.getString("iib.invalidmethod"));
                }
            }
            parsingRequestLinePhase = 3;
        }
        if (parsingRequestLinePhase == 3) {
            // Spec says single SP but also be tolerant of multiple SP and/or HT
            boolean space = true;
            while (space) {
                // Read new bytes if needed
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }
                byte chr = byteBuffer.get();
                if (!(chr == Constants.SP || chr == Constants.HT)) {
                    space = false;
                    byteBuffer.position(byteBuffer.position() - 1);
                }
            }
            parsingRequestLineStart = byteBuffer.position();
            parsingRequestLinePhase = 4;
        }
        if (parsingRequestLinePhase == 4) {
            // Mark the current buffer position

            int end = 0;
            //
            // Reading the URI
            //
            boolean space = false;
            while (!space) {
                // Read new bytes if needed
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }
                int pos = byteBuffer.position();
                byte chr = byteBuffer.get();
                if (chr == Constants.SP || chr == Constants.HT) {
                    space = true;
                    end = pos;
                } else if (chr == Constants.CR || chr == Constants.LF) {
                    // HTTP/0.9 style request
                    parsingRequestLineEol = true;
                    space = true;
                    end = pos;
                } else if (chr == Constants.QUESTION && parsingRequestLineQPos == -1) {
                    parsingRequestLineQPos = pos;
                } else if (HttpParser.isNotRequestTarget(chr)) {
                    throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget"));
                }
            }
            if (parsingRequestLineQPos >= 0) {
                request.queryString().setBytes(byteBuffer.array(), parsingRequestLineQPos + 1,
                        end - parsingRequestLineQPos - 1);
                request.requestURI().setBytes(byteBuffer.array(), parsingRequestLineStart,
                        parsingRequestLineQPos - parsingRequestLineStart);
            } else {
                request.requestURI().setBytes(byteBuffer.array(), parsingRequestLineStart,
                        end - parsingRequestLineStart);
            }
            parsingRequestLinePhase = 5;
        }
        if (parsingRequestLinePhase == 5) {
            // Spec says single SP but also be tolerant of multiple and/or HT
            boolean space = true;
            while (space) {
                // Read new bytes if needed
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }
                byte chr = byteBuffer.get();
                if (!(chr == Constants.SP || chr == Constants.HT)) {
                    space = false;
                    byteBuffer.position(byteBuffer.position() - 1);
                }
            }
            parsingRequestLineStart = byteBuffer.position();
            parsingRequestLinePhase = 6;

            // Mark the current buffer position
            end = 0;
        }
        if (parsingRequestLinePhase == 6) {
            //
            // Reading the protocol
            // Protocol is always "HTTP/" DIGIT "." DIGIT
            //
            while (!parsingRequestLineEol) {
                // Read new bytes if needed
                if (byteBuffer.position() >= byteBuffer.limit()) {
                    if (!fill(false)) // request line parsing
                        return false;
                }

                int pos = byteBuffer.position();
                byte chr = byteBuffer.get();
                if (chr == Constants.CR) {
                    end = pos;
                } else if (chr == Constants.LF) {
                    if (end == 0) {
                        end = pos;
                    }
                    parsingRequestLineEol = true;
                } else if (!HttpParser.isHttpProtocol(chr)) {
                    throw new IllegalArgumentException(sm.getString("iib.invalidHttpProtocol"));
                }
            }

            if ((end - parsingRequestLineStart) > 0) {
                request.protocol().setBytes(byteBuffer.array(), parsingRequestLineStart,
                        end - parsingRequestLineStart);
            } else {
                request.protocol().setString("");
            }
            parsingRequestLine = false;
            parsingRequestLinePhase = 0;
            parsingRequestLineEol = false;
            parsingRequestLineStart = 0;
            return true;
        }
        throw new IllegalStateException(
                "Invalid request line parse phase:" + parsingRequestLinePhase);
    }

    private boolean fill(boolean block) throws Exception {
        // 判断一下是否在解析过程中
        if (parsingHeader) {
            // 判断一下解析过程，byteBuffer容量是不是不够
            if (byteBuffer.limit() >= headerBufferSize) {
                throw new IllegalArgumentException();
            }
        } else { // 不是在解析过程中，也就是说明解析完了标记一下。
            byteBuffer.limit(end).position(end);
        }

        byteBuffer.mark();
        // position < limit 说明byteBuffer中的数据没有用完
        if (byteBuffer.position() < byteBuffer.limit()) {
            // 没有用完不能随便覆盖，强行把position指针移到limit处
            byteBuffer.position(byteBuffer.limit());
        }
        // 把limit变成最大容量，准备读取数据
        byteBuffer.limit(byteBuffer.capacity());

        int nRead = nioChannelWrapper.read(block, byteBuffer);
        byteBuffer.limit(byteBuffer.position()).reset();
        if (nRead > 0) {
            return true;
        } else if (nRead == -1) {
            throw new Exception();
        } else {
            return false;
        }
    }

}
