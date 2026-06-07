package baio.io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class RandomAccessFileExample {

    static void main() throws IOException {
//        try (RandomAccessFile file = new RandomAccessFile("test.text", "rw")) {
//            file.seek(10);
//            file.write("这是什么?".getBytes(StandardCharsets.UTF_8));
//            file.seek(100);
//            file.writeInt(32);
//            // seek to position 100 and read int.
//            file.seek(100);
//            int val = file.readInt();
//            assert val == 32;
//        }

        DownloadFile file = new DownloadFile("https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/");
        file.download("apache-maven-3.9.16-bin.tar.gz");
    }

    static class DownloadFile {
        private final String url;

        public DownloadFile(String url) {
            this.url = url;
        }

        public void download(String filename) throws IOException {
            // download file from url.
            File file = new File(filename);
            long downloaded = file.exists() ? file.length() : 0;
            System.out.println("File " + filename + " downloaded to " + downloaded);
            String fileUrl = url + "/" + filename;
            HttpURLConnection conn = (HttpURLConnection) URI.create(fileUrl).toURL().openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setRequestMethod("GET");
            // Http Range
            conn.setRequestProperty("Range", "bytes=" + downloaded + "-");

            try (InputStream is = conn.getInputStream();
                 RandomAccessFile raf = new RandomAccessFile(file, "rwd")
            ) {
                raf.seek(downloaded);

                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    downloaded += len;
                    System.out.println("Downloaded " + downloaded + " bytes");
                    raf.write(buf, 0, len);
                }
            }
            System.out.println("File " + filename + " downloaded to " + downloaded);
        }
    }
}
