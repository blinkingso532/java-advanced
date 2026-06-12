package io;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.Scanner;

public class BufferedCopyCharacters {
    public static void main(String[] args) {
        try (BufferedReader br = new BufferedReader(new FileReader("test.txt"))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("======================");
        try (Scanner sc = new Scanner(new FileInputStream("test.txt"))) {
            sc.useDelimiter(",\\s*");
            while (sc.hasNext()) {
                System.out.println(sc.next());
            }
        } catch (Exception e) {
            // TODO: handle exception
        }

    }
}
