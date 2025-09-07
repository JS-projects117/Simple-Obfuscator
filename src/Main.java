import java.io.*;
import java.util.Scanner;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) throws IOException {

        File file = new File("src/example.txt");
        Scanner scnr = new Scanner(file);


        BufferedWriter write = new BufferedWriter(new FileWriter("src/ObfuscatedFile"));


        while(scnr.hasNext()){
            write.write(scnr.next() + "\n");

        }

        write.close();



    }
}