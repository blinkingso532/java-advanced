package baio.io;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 阻塞 IO 示例
 * <p>
 * 阻塞 IO 示例，展示如何使用阻塞 IO 进行文件读写。
 * 阻塞 IO 是最简单的 IO 模型，每个线程只能处理一个请求，直到请求完成。
 * 这种 IO 模型在高并发场景下效率较低，因为每个请求都需要等待其他请求完成。
 */
public class BlockingIOExample {

    void main() {
//        testReadFile();
//        testWriteFile();
        // 测试读取ByteArray
//        testReadByteArray();
        testByteArrayOutputStream();
    }

    static void testByteArrayOutputStream() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            bos.write("hello你好啊~~~".getBytes(StandardCharsets.UTF_8));
            System.out.println(bos.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            error(e);
        }
    }

    static void testReadFile() {
        FileExample fileExample = new FileExample("oom.md");
        byte[] bytes = fileExample.readFile();
        if (bytes.length == 0) {
            System.out.println("file is empty");
            return;
        }
        System.out.println(new String(bytes, StandardCharsets.UTF_8));
    }

    static void testReadByteArray() {
        byte[] bytes = new byte[1024];
        Arrays.fill(bytes, (byte) 'a');
        new FileExample.ByteArrayExample(bytes).readBytes();
    }

    // 测试写入文件，先写入hello world，再写入hello java，最后读取文件内容
    // 期望输出：hello world hello java
    static void testWriteFile() {
        FileExample fileExample = new FileExample("test.text");
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
        fileExample.writeFile(bytes);
        byte[] bytes2 = " hello java".getBytes(StandardCharsets.UTF_8);
        fileExample.appendFile(bytes2);
        fileExample.writeLn();
        fileExample.writeInt(100);
        byte[] bytes3 = fileExample.readFile();
        System.out.println(new String(bytes3, StandardCharsets.UTF_8));
    }


    static final class FileExample {
        // 文件读写, FileInputStream, FileOutputStream
        private final String filePath;

        public FileExample(String filePath) {
            this.filePath = filePath;
        }

        public String getFilePath() {
            return this.filePath;
        }

        byte[] readFile() {
            byte[] fileBytes = new byte[0];
            try (InputStream is = new FileInputStream(filePath)) {
                byte[] bytes = new byte[1024];
                int len;
                // 读取文件，每次读取1024字节，如果返回len为-1，表明读取完成
                while ((len = is.read(bytes)) != -1) {
                    // 扩印读取到的字节数
                    System.out.println("read " + len + " bytes");
                    // 扩展数组大小
                    int destStart = fileBytes.length;
                    fileBytes = Arrays.copyOf(fileBytes, fileBytes.length + len);
                    // 数组赋值，将读取到的字节添加到 fileBytes 数组中
                    System.arraycopy(bytes, 0, fileBytes, destStart, len);
                }
            } catch (IOException e) {
                error(e);
            }
            return fileBytes;
        }

        public void writeFile(byte[] bytes) {
            try (OutputStream os = new FileOutputStream(filePath)) {
                os.write(bytes);
            } catch (IOException e) {
                error(e);
            }
        }

        public void appendFile(byte[] bytes) {
            try (OutputStream os = new FileOutputStream(filePath, true)) {
                os.write(bytes);
            } catch (IOException e) {
                error(e);
            }
        }

        public void writeLn() {
            try (FileOutputStream os = new FileOutputStream(filePath, true)) {
                os.write("\n".getBytes(StandardCharsets.UTF_8));
                // 直接刷盘
                os.getFD().sync();
            } catch (IOException e) {
                error(e);
            }
        }

        public void writeInt(int value) {
            try (OutputStream os = new FileOutputStream(filePath, true)) {
                os.write(value);
            } catch (IOException e) {
                error(e);
            }
        }

        static final class ByteArrayExample {
            private final byte[] bytes;
            private final ByteArrayInputStream bais;

            public ByteArrayExample(byte[] bytes) {
                this.bytes = bytes;
                this.bais = new ByteArrayInputStream(bytes);
            }

            public byte[] getBytes() {
                return bytes;
            }

            public ByteArrayInputStream getBais() {
                return bais;
            }

            public void readBytes() {
                try (InputStream is = bais) {
                    byte[] bytes = new byte[bais.available()];
                    int len = is.read(bytes);
                    System.out.println("read " + len + " bytes");
                    System.out.println(new String(bytes, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    error(e);
                }
            }
        }
    }


    public static void error(Exception e) {
        System.out.println("-----------------");
        System.out.println("Got error: " + e.getMessage());
        System.out.println("-----------------");
    }
}

