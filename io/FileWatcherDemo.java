package io;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * NIO.2 WatchService 与 FileVisitor 综合演示
 *
 * 功能：
 *   1. 递归注册目录监听
 *   2. 实时输出所有文件变更事件
 *   3. 演示 FileVisitor 做初始扫描
 *
 * 测试：
 *   1. 运行后在该目录下创建/修改/删除文件
 *   2. 观察控制台输出
 */
public class FileWatcherDemo {

    public static void main(String[] args) throws IOException, InterruptedException {
        Path watchDir = Paths.get(".").toAbsolutePath().normalize();
        System.out.println("监控目录: " + watchDir);
        System.out.println("支持的事件: 创建(ENTRY_CREATE) / 修改(ENTRY_MODIFY) / 删除(ENTRY_DELETE)");
        System.out.println("按 Ctrl+C 退出\n");

        WatchService watcher = FileSystems.getDefault().newWatchService();

        // 1. 递归注册所有子目录
        Files.walkFileTree(watchDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });

        System.out.println("已注册 " + countDirs(watchDir) + " 个目录的监听\n");

        // 2. 事件循环
        while (true) {
            WatchKey key = watcher.take(); // 阻塞等待事件

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                // OVERFLOW 事件：事件丢失的信号
                if (kind == OVERFLOW) {
                    System.out.println("⚠ 事件溢出，部分文件变更可能丢失！");
                    continue;
                }

                // event.context() 返回相对路径（仅文件名）
                Path filename = (Path) event.context();
                // 完整路径 = 注册的目录 + 文件名
                Path dir = (Path) key.watchable();
                Path fullPath = dir.resolve(filename);

                System.out.printf("[%s] %s%n", kind.name(), fullPath);

                // 如果新建的是目录，自动注册监听
                if (kind == ENTRY_CREATE && fullPath.toFile().isDirectory()) {
                    registerRecursive(watcher, fullPath);
                    System.out.println("  → 已自动注册新目录的监听");
                }
            }

            // ★ 关键：必须调用 reset() 才能继续接收事件
            boolean valid = key.reset();
            if (!valid) {
                // 目录已被删除或无法访问
                System.out.println("目录已不可用，停止监听: " + key.watchable());
            }
        }
    }

    private static void registerRecursive(WatchService watcher, Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs)
                        throws IOException {
                    d.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("注册目录失败: " + dir);
        }
    }

    private static int countDirs(Path root) throws IOException {
        // 使用 Files.walk（懒加载流）
        try (var stream = Files.walk(root)) {
            return (int) stream.filter(Files::isDirectory).count();
        }
    }
}
