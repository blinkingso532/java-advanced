package io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * NIO Selector 版 Echo Server — 多路复用的最小可运行示例
 *
 * 演示要点：
 *   1. 单个线程通过 Selector 管理多个连接
 *   2. OP_ACCEPT / OP_READ / OP_WRITE 的注册和处理
 *   3. 为什么必须 iterator.remove()
 *   4. 处理 read 返回 -1（对端关闭连接）
 *   5. 为什么 OP_WRITE 一般不应一直注册（空轮询问题）
 *
 * 测试方法：
 *   1. 启动本类 main
 *   2. 开多个终端执行：telnet localhost 8888
 *   3. 输入任意文本，观察回显
 */
public class EchoServerNIO {

    private static final int PORT = 8888;
    private static final int BUFFER_SIZE = 256;

    public static void main(String[] args) throws IOException {
        Selector selector = Selector.open();

        // 1. 打开 ServerSocketChannel 并注册到 Selector
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(PORT));
        serverChannel.configureBlocking(false); // 必须设置为非阻塞
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("Echo Server 启动，监听端口 " + PORT);

        // 2. 事件循环
        while (true) {
            // select() 阻塞，直到至少有一个 Channel 就绪
            int readyChannels = selector.select();
            if (readyChannels == 0) {
                continue;
            }

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();

                // ===== 处理 Accept 事件 =====
                if (key.isAcceptable()) {
                    ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
                    SocketChannel clientChannel = ssc.accept();
                    if (clientChannel != null) {
                        clientChannel.configureBlocking(false);
                        // 将客户端通道注册到同一个 Selector，监听读事件
                        clientChannel.register(selector, SelectionKey.OP_READ,
                                ByteBuffer.allocate(BUFFER_SIZE)); // attachment
                        System.out.println("新连接: " + clientChannel.getRemoteAddress());
                    }
                }

                // ===== 处理 Read 事件 =====
                if (key.isReadable()) {
                    SocketChannel clientChannel = (SocketChannel) key.channel();
                    ByteBuffer buf = (ByteBuffer) key.attachment();

                    try {
                        int bytesRead = clientChannel.read(buf);
                        if (bytesRead == -1) {
                            // 对端正常关闭连接
                            System.out.println("连接关闭: " + clientChannel.getRemoteAddress());
                            clientChannel.close();
                        } else if (bytesRead > 0) {
                            buf.flip();
                            byte[] data = new byte[buf.remaining()];
                            buf.get(data);
                            String received = new String(data).trim();
                            System.out.println("收到 [" + clientChannel.getRemoteAddress() + "]: " + received);

                            // 回显：将数据写回客户端
                            buf.flip(); // 重置 position 用于写入
                            clientChannel.write(buf);

                            if (buf.hasRemaining()) {
                                // 数据没写完（Socket 发送缓冲区满），注册 OP_WRITE
                                key.interestOps(SelectionKey.OP_WRITE);
                            }

                            buf.clear();
                        }
                    } catch (IOException e) {
                        // 客户端异常断开
                        System.out.println("连接异常: " + e.getMessage());
                        clientChannel.close();
                    }
                }

                // ===== 处理 Write 事件 =====
                if (key.isWritable()) {
                    SocketChannel clientChannel = (SocketChannel) key.channel();
                    ByteBuffer buf = (ByteBuffer) key.attachment();

                    clientChannel.write(buf);
                    if (!buf.hasRemaining()) {
                        // 写完了，恢复为监听读
                        key.interestOps(SelectionKey.OP_READ);
                    }
                }

                // ★ 关键！必须移除已处理的 key
                // 否则下一次 select 会认为这个 key 仍在就绪集合中
                keyIterator.remove();
            }
        }
    }
}
