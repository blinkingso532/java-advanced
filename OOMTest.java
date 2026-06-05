import java.util.ArrayList;
import java.util.List;

public class OOMTest {
    // 通过 -Xmx10m -Xms10m -XX:+HeapDumpOnOutOfMemoryError
    // -XX:HeapDumpPath=./dump.hprof 来触发堆溢出异常
    // 并生成 dump.hprof 文件

    // javac OOMTest.java
    // java -Xms10m -Xmx10m -XX:+HeapDumpOnOutOfMemoryError
    // -XX:HeapDumpPath=./dump.hprof OOMTest

    private static final String TEST_NAME = "OOMTestUser";

    public static void main(String[] args) {
        int count = 0;
        List<int[]> list = new ArrayList<>();
        System.out.println("Testing OOM and analysis! I am a " + TEST_NAME);
        while (true) {
            count++;
            int[] array = new int[1024 * 1024]; // 1MB
            System.out.println("Array size: " + array.length + ", count: " + count);
            list.add(array);
        }
    }
}
