import java.io.*;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;

public class Main {

    static final String[] wordsToIgnore = {//removes words that contains the word at all
            "Math",
            "Random",
            "foreach",
            "switch",
            "case",
            "Update",
            "Start",
            "while",
            "return",
            "catch",
            "Compare",
            "[",
            "Awake",
            "if",
            "for"

    };

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

    private static void findAllFiles(LinkedList<File> fileList, String path) {


        for (File x : new File(path).listFiles()) {
            if (x.isDirectory()) {
                findAllFiles(fileList, x.getPath());

            } else {
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

                String bestMatch = "";
                boolean foundMatch = false;
                for (String varName : varMap.keySet()) {
                    if (line.contains(varName)) {
                        if (bestMatch.equals("")) {
                            foundMatch = true;
                            bestMatch = varName;

                        } else if (bestMatch.length() < varName.length()) {
                            bestMatch = varName;
                        }
                    }
                }

                if(!bestMatch.equals(""))
              newLine = line.replace(bestMatch, varMap.get(bestMatch));

                if (!bestMatch.equals("")) {
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

        String[] keySet = varMap.keySet().toArray(new String[0]);
        for (String key : keySet) {

            for(String word : wordsToIgnore){
               if( key.contains(word)){
                   varMap.remove(key);
               }
            }
            if(key.contains(".")){
                varMap.remove(key);

            } else if (key.contains("<") && !key.contains("T")) {
                varMap.put( varMap.get(key).split("<")[0], "");
                varMap.remove(key);

            }
            else{
                varMap.remove(key);
            }
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

        for (File file : files) {


            Scanner scnr = new Scanner(file);
            while (scnr.hasNextLine()) {
                String line = scnr.nextLine();

   if (line.contains("class")) {
                    int startIndex = line.indexOf("class") + "class".length() + 1;

                    int endIndex = 0;

                    if (line.substring(startIndex).contains(" ")) {
                        endIndex = line.substring(startIndex).indexOf(' ') + startIndex;
                    } else if (line.substring(startIndex).contains("{")) {
                        endIndex = line.substring(startIndex).indexOf('{') + startIndex;
                    } else {
                        endIndex = line.substring(startIndex).length() + startIndex;
                    }

                    if (debug)
                        System.out.println(line.substring(startIndex, endIndex));
                    try {
                        variableMap.put(line.substring(startIndex, endIndex), "");
                    } catch (Exception e) {
                        System.out.println(line);
                        System.out.println(e);
                    }
                }
                    else if (line.contains("(")) {
                        int endIndex = line.indexOf("(");

                        while (line.charAt(endIndex - 1) == ' ' || line.charAt(endIndex) == ' ') {
                            endIndex -= 1;
                        }
                        int startIndex = 0;


                        for (int i = endIndex; i >= 0 && line.charAt(i) != ' '; --i) {
                            startIndex = i;
                        }

                        if (line.charAt(endIndex) != ' ' && line.charAt(endIndex) != '(')
                            ++endIndex;

                        if (variableMap.get(line.substring(startIndex, endIndex)) == null) {
                            variableMap.put(line.substring(startIndex, endIndex), "");
                        }
                    }


            else if (line.contains(";") && !line.contains("import") && !line.contains("using")) {

                    if (line.contains("=")) {
                        int endIndex = line.indexOf("=");

                        while (endIndex > 0 && line.charAt(endIndex - 1) == ' ') {
                            endIndex -= 1;

                        }

                        int startIndex = 0;


                        for (int i = endIndex + 1; i >= 0 && line.charAt(i) != ' '; --i) {
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