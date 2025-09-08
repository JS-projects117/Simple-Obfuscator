import java.io.*;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws IOException {

        // File file = new File("src/filedForObfuscation/example.txt");

        String filePath = "src/filesForObfuscation";
        while (true) {

            if (new File(filePath).isDirectory()) {
                filePath += "/" + new File(filePath).getName();
            } else {
                break;
            }
        }


LinkedList<File> fileList = new LinkedList<>();

        findAllFiles(fileList, "src/filesForObfuscation");

        File[] files = fileList.toArray(new File[0]);
        HashMap<String, String> variableMap = new HashMap<>();


        loadVariables(variableMap, files);

        for (String key : variableMap.keySet()) {
            System.out.println(key + "     val: " + variableMap.get(key));
        }
        obfuscate(variableMap, files);

        for (String key : variableMap.keySet()) {
              System.out.println(key + "     val: " + variableMap.get(key));
        }


    }

    private static void findAllFiles(LinkedList<File> fileList, String path){


        for(File x : new File(path).listFiles()){
            if(x.isDirectory()){
                findAllFiles(fileList, x.getPath());

            }
            else{
                fileList.add(x);
            }
        }
    }

    private static void obfuscate(HashMap<String, String> varMap, File[] files) throws IOException {
        genNewNames(varMap);

        for (File file : files) {
            Scanner scnr = new Scanner(file);
            BufferedWriter write = new BufferedWriter(new FileWriter("src/ObfuscatedFiles/" + file.getName()));


            while (scnr.hasNextLine()) {
                String line = scnr.nextLine() + "\n";
                String newLine = "";

                boolean foundMatch = false;
                for (String varName : varMap.keySet()) {
                    if (line.contains(varName)) {
                        foundMatch = true;

                        newLine = line.replace(varName, varMap.get(varName));

                    }
                }
                if (foundMatch) {
                    write.write(newLine);
                } else {
                    write.write(line);
                }
            }

            write.close();
        }
    }

    private static void genNewNames(HashMap<String, String> varMap) {

        List<String> wordBank = new LinkedList<>();

        String[] letters = "abcdefghijklmnopqrstuvwxyz".split("");

        for (int i = 0; i < letters.length; ++i) {
            wordBank.add(letters[i]);
        }

        int j = 0;
        for (String key : varMap.keySet()) {
            String newWord = GenerateWord("", wordBank, j);
            varMap.replace(key, newWord);
            ++j;
        }
    }

    private static String GenerateWord(String word, List<String> wordBank, int index) {
        int bankLen = wordBank.size(); // simpler than toArray().length

        if (index >= bankLen) {

            return GenerateWord(word + wordBank.get(index % bankLen), wordBank, index - bankLen);
        } else {

            return word + wordBank.get(index);
        }
    }


    private static void loadVariables(HashMap<String, String> variableMap, File[] files) throws FileNotFoundException {
        boolean debug = false;

for(File file : files){


    Scanner scnr = new Scanner(file);
        while(scnr.hasNextLine()) {
            String line = scnr.nextLine();
            if (line.contains("public") || line.contains("private") || line.contains("protected") || line.contains("void")) {
                if (line.contains("class")) {
                    int startIndex = line.indexOf("class") + "class".length() + 1;

                    int endIndex = 0;

                    if(line.substring(startIndex).contains(" ")){
                        endIndex = line.substring(startIndex).indexOf(' ') + startIndex;
                    }else if(line.substring(startIndex).contains("{")){
                        endIndex = line.substring(startIndex).indexOf(' ') + startIndex;
                    }
                    else{
                        endIndex = line.substring(startIndex).length() + startIndex;
                    }

                    if (debug)
                        System.out.println(line.substring(startIndex, endIndex));
                    try {
                        variableMap.put(line.substring(startIndex, endIndex), "");
                    }
                    catch (Exception e){
                        System.out.println(line);
                        System.out.println(e);
                    }
                }
                else if (line.contains("(")) {
                    int endIndex = line.indexOf("(");

                    while (line.charAt(endIndex -1) == ' ' || line.charAt(endIndex) == ' '){
                        endIndex -= 1;
                    }
                    int startIndex = 0;


                    for (int i = endIndex;i >= 0 && line.charAt(i) != ' '; --i) {
                        startIndex = i;
                    }

                    if(line.charAt(endIndex) != ' ' && line.charAt(endIndex) != '(')
                        ++endIndex;

//                    System.out.println(startIndex + "     end =  " + endIndex);
//                    System.out.println(line.substring(startIndex, endIndex));
                    if (variableMap.get(line.substring(startIndex, endIndex)) == null) {
                        variableMap.put(line.substring(startIndex, endIndex), "");
                    }
                }
//                else if (line.contains("(")) {
//                    int endIndex = line.indexOf("(");
//                    int startIndex = 0;
//                    for (int i = line.indexOf("("); line.charAt(i) != ' '; --i) {
//                        startIndex = i;
//
//                    }
//
//                    if (debug)
//                        System.out.println(line.substring(startIndex, endIndex));
//
//                    if (variableMap.get(line.substring(startIndex, endIndex)) == null) {
//                        variableMap.put(line.substring(startIndex, endIndex), "");
//                    }
//                }

            }
            else if (line.contains(";")) {
                if (line.contains("=")) {
                    int endIndex = line.indexOf("=");

                    while (line.charAt(endIndex -1) == ' ' || line.charAt(endIndex) == ' '){
                        endIndex -= 1;
                    }
                    int startIndex = 0;


                    for (int i = endIndex;i >= 0 && line.charAt(i) != ' '; --i) {
                            startIndex = i;
                        }
                    endIndex += 1;
//                    System.out.println(startIndex + "     end =  " + endIndex);
//                    System.out.println(line.substring(startIndex, endIndex));
                    if (variableMap.get(line.substring(startIndex, endIndex)) == null) {
                        variableMap.put(line.substring(startIndex, endIndex), "");
                    }
                }
            }
        }


        }
    }
}