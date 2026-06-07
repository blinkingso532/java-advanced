package baio.io;

import java.io.*;

public class ObjectStreamExample {

    static void main() throws IOException {
        Person p = new Person(1, "张三", 18);
        Person p2 = new Person(2, "李四", 18);
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("test.obj"))) {
            oos.writeObject(p);
            oos.writeObject(p2);
        }

        try(ObjectInputStream ois = new ObjectInputStream(new FileInputStream("test.obj"))) {
            Person p3 = (Person) ois.readObject();
            System.out.println(p3);
            Person p4 = (Person) ois.readObject();
            System.out.println(p4);
        } catch(ClassNotFoundException e) {
            e.printStackTrace();
        }

        try(DataOutputStream dos = new DataOutputStream(System.out)) {
            dos.write("CAFEBABE".getBytes());
            dos.writeByte(12);
        }
    }


    record Person(int id, String name, int age) implements Serializable {
        @Serial
        private static final long serialVersionUID = 20230824150000L;
    }
}
