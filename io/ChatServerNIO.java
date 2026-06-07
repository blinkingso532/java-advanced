package io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实践项目：NIO 多客户端聊天室
 *
 * 功能：
 *   - 用户上线/下线广播
 *   - 私聊: /msg <user> <content>
 *   - 在线用户列表: /users
 *   - 昵称设置: /nick <name>
 *
 * 技术要点：
 *   - 单线程 Selector 管理所有连接
 *   - 粘包/拆包处理（行分隔符方案：\n）
 *   - Attachment 存储每个连接的状态
 */
public class ChatServerNIO {

    private static final int PORT = 9999;
    // 所有在线用户（channel → nickname）
    private static final Map<SocketChannel, String> users = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        Selector selector = Selector.open();

        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(PORT));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("聊天室服务端启动，端口 " + PORT);

        while (true) {
            selector.select();
            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> iter = keys.iterator();

            while (iter.hasNext()) {
                SelectionKey key = iter.next();

                if (key.isAcceptable()) {
                    handleAccept(key, selector);
                }
                if (key.isReadable()) {
                    handleRead(key);
                }
                iter.remove();
            }
        }
    }

    private static void handleAccept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel client = ssc.accept();
        if (client == null) return;

        client.configureBlocking(false);
        // attachment: 每个连接独立的读 Buffer + 消息解析用 StringBuilder
        ClientContext ctx = new ClientContext();
        client.register(selector, SelectionKey.OP_READ, ctx);

        // 分配默认昵称
        String nick = "User-" + client.getRemoteAddress().toString().substring(1);
        users.put(client, nick);
        broadcast(null, "[系统] " + nick + " 加入了聊天室");
        sendMessage(client, "欢迎 " + nick + "！输入 /help 查看命令");
    }

    private static void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ClientContext ctx = (ClientContext) key.attachment();
        ByteBuffer buf = ctx.readBuffer;

        try {
            int n = client.read(buf);
            if (n == -1) {
                disconnect(client);
                return;
            }
            if (n == 0) return;

            buf.flip();
            while (buf.hasRemaining()) {
                byte b = buf.get();
                if (b == '\n') {
                    // 完整的一行消息
                    processMessage(client, ctx.lineBuilder.toString().trim());
                    ctx.lineBuilder.setLength(0);
                } else {
                    ctx.lineBuilder.append((char) b);
                }
            }
            buf.clear();
        } catch (IOException e) {
            disconnect(client);
        }
    }

    private static void processMessage(SocketChannel sender, String msg) throws IOException {
        if (msg.isEmpty()) return;

        String nick = users.get(sender);

        // 命令处理
        if (msg.startsWith("/")) {
            String[] parts = msg.split("\\s+", 3);
            switch (parts[0].toLowerCase()) {
                case "/nick":
                    if (parts.length >= 2) {
                        String oldNick = nick;
                        String newNick = parts[1];
                        users.put(sender, newNick);
                        broadcast(sender, "[系统] " + oldNick + " 改名为 " + newNick);
                    }
                    break;
                case "/users":
                    sendMessage(sender, "在线用户: " + String.join(", ", users.values()));
                    break;
                case "/msg":
                    if (parts.length >= 3) {
                        String target = parts[1];
                        String content = parts[2];
                        users.entrySet().stream()
                                .filter(e -> e.getValue().equals(target))
                                .findFirst()
                                .ifPresentOrElse(
                                        e -> sendMessage(e.getKey(), "[私聊 " + nick + "] " + content),
                                        () -> sendMessage(sender, "用户 " + target + " 不存在")
                                );
                    }
                    break;
                case "/help":
                    sendMessage(sender, "命令: /nick <name> | /users | /msg <user> <msg> | /help");
                    break;
                default:
                    sendMessage(sender, "未知命令，输入 /help 查看帮助");
            }
        } else {
            // 普通消息 — 广播
            broadcast(sender, nick + ": " + msg);
        }
    }

    // ────────── 消息发送 ──────────
    // 简化版：直接 write（生产环境应处理 OP_WRITE + 写队列）

    private static void sendMessage(SocketChannel client, String message) {
        try {
            ByteBuffer buf = ByteBuffer.wrap((message + "\n").getBytes());
            while (buf.hasRemaining()) {
                client.write(buf);
            }
        } catch (IOException e) {
            // 忽略发送失败的连接
        }
    }

    private static void broadcast(SocketChannel sender, String message) {
        ByteBuffer buf = ByteBuffer.wrap((message + "\n").getBytes());
        for (SocketChannel client : users.keySet()) {
            if (client == sender) continue;
            try {
                buf.rewind();
                while (buf.hasRemaining()) {
                    client.write(buf);
                }
            } catch (IOException e) {
                // 忽略单个客户端的发送失败
            }
        }
    }

    private static void disconnect(SocketChannel client) {
        String nick = users.remove(client);
        if (nick != null) {
            broadcast(null, "[系统] " + nick + " 离开了聊天室");
        }
        try {
            client.close();
        } catch (IOException ignored) {}
    }

    // ────────── 每个连接的上下文 ──────────

    static class ClientContext {
        ByteBuffer readBuffer = ByteBuffer.allocate(1024);
        StringBuilder lineBuilder = new StringBuilder();
    }
}
