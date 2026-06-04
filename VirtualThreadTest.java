import java.time.Duration;

public final class VirtualThreadTest implements UserService {

    public static final String USER_NAME = "VirtualThreadUser";
    private static int virtualThreadCount = 0;
    private String email = "virtual.thread.user@example.com_000000000";
    // Test lock.
    private final Object lock = new Object();

    public VirtualThreadTest() {
        this.email = "virtual.thread.user@example.com";
    }

    public VirtualThreadTest(String email) {
        this.email = email;
    }

    @Override
    public String getUserName() {
        return USER_NAME;
    }

    public static void main(String[] args) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(1));
                virtualThreadCount++;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("Hello, Virtual Thread!");
        });
        System.out.println("Hello, World!");

        Thread.ofPlatform()
                .name("CarrierThread")
                .daemon()
                .inheritInheritableThreadLocals(true)
                .stackSize(1024)
                .start(() -> {
                    try {
                        Thread.sleep(Duration.ofSeconds(1));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    System.out.println("Hello, Platform Thread!");
                });
    }

    public void testPrint() {
        String userNameForTestingClass = getUserName();
        System.out.println("Im glade to be a " + userNameForTestingClass);
    }
}
