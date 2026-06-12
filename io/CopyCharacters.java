package io;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 请注意，CopyBytes和CopyCharacters都使用int变量进行读写操作。
 * 但在CopyCharacters中，int变量的最后16位存储字符值；
 * 而在CopyBytes中，int变量的最后8位存储byte值。
 */
public class CopyCharacters {

    public static void main(String[] args) {
        // 复制文件 test.txt 到 test_copy.txt, 通过try-with-resources自动关闭流资源
        try (FileReader fr = new FileReader("test.txt");
                FileWriter fw = new FileWriter("test_copy.txt")) {
            int ch;
            while ((ch = fr.read()) != -1) {
                fw.write(ch);
                // 打印当前字符的值
                System.out.println("Current character value: " + ch);
                int chValue = ch & 0xFFFF;
                System.out.println("last 16bits char is: " + (char) chValue);
                int prev = ch >> 16; // 右移16位获取前16位
                System.out.println("prev 16bits char is: " + prev);
                if (Character.isHighSurrogate('\uD83D')) {
                    System.out.println("is high surrogate");
                    String emoji = new String(new char[] { (char) '\uD83D', (char) '\uDC22' });
                    System.out.println(emoji);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
