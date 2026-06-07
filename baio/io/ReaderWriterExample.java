package baio.io;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ReaderWriterExample {

    void main() throws IOException {
//        testReadSystemIn();
//        testWriteSystemOut();
//        testWriteReadWithEncoding();
//        testWithFileReaderAndWriter();
        testBufferedInputStream();
    }

    static void testBufferedInputStream() throws IOException {
        String filePath = "test.text";
        try(BufferedInputStream bis = new BufferedInputStream(new FileInputStream(filePath))) {
            System.out.println("available " + bis.available());
            bis.mark(10);
            int avail = bis.available();
            System.out.println("available " + avail);
            byte[] content = bis.readAllBytes();
            System.out.println(new String(content, StandardCharsets.UTF_8));
        }
    }


    static void testReadSystemIn() throws IOException {
        InputStreamReader isr = new InputStreamReader(System.in);
        BufferedReader br = new BufferedReader(isr);
        String line = br.readLine();
        while (!"exit".equals(line)) {
            System.out.println(line);
            line = br.readLine();
        }

        try(InputStreamReader isr2 = new InputStreamReader(System.in, StandardCharsets.UTF_8)) {
            isr2.readAllLines().forEach(System.out::println);
            System.out.println("----->");
        }
    }

    static void testWriteSystemOut() throws IOException {
        OutputStreamWriter writer = new OutputStreamWriter(System.out);
        BufferedWriter bw = new BufferedWriter(writer);
        bw.write('c');
        bw.write('\n');
        bw.write("Hello World!");
        bw.write('\n');
        bw.write("You are a good student!", 2, 10);
        bw.flush();
    }

    static void testWriteReadWithEncoding() throws IOException {
        String filePath = "test.text";
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(filePath, true))) {
            BufferedWriter bw = new BufferedWriter(writer);
            bw.write(new String("你好啊。。。。。".toCharArray()));
            bw.write((int) 'c');
            bw.write((int) '\n');
            bw.write("Hello World!");
            bw.write((int) '\n');
            bw.write("You are a good student!", 2, 10);
            bw.write(new char[]{'\n', '逆', '#', '\n'});
            bw.flush();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // read throw bytes.
        try (InputStream is = new FileInputStream(filePath)) {
            byte[] bytes = new byte[is.available()];
            int len = is.read(bytes);
            System.out.println(new String(bytes, 0, len, StandardCharsets.UTF_8));
        }
    }

    void testWithFileReaderAndWriter() throws IOException {
        String filePath = "test.text";
        try (FileReader fr = new FileReader(filePath)) {
            List<String> lines = fr.readAllLines();
            for (String line : lines) {
                System.out.println("---->" + line);
            }
        }
    }
}
