package io;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class CopyBytes {
    public static void main(String[] args) {
        // 复制文件 test.text 到 test_copy.text, 通过try-with-resources自动关闭流资源
        try (FileInputStream fis = new FileInputStream("test.txt");
                FileOutputStream fos = new FileOutputStream("test_copy.txt")) {
            int ch;
            while ((ch = fis.read()) != -1) {
                fos.write(ch);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
