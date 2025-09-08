import java.io.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) throws IOException {

        File file = new File("src/example.txt");
        Scanner scnr = new Scanner(file);
        HashMap<String, String> variableMap = new HashMap<>();

boolean obfuscateMethods = true;
boolean debug = false;



        while(scnr.hasNext()){
            String line = scnr.nextLine();
            if(line.contains("public") || line.contains("private") || line.contains("protected") || line.contains("void")){
                if(line.contains("class")){
                    int startIndex = line.indexOf("class") + "class".length() + 1;
                    int endIndex = line.substring(startIndex).indexOf(' ') + startIndex;

                    if(debug)
                    System.out.println(line.substring(startIndex, endIndex));
variableMap.put(line.substring(startIndex, endIndex), "");
                }
                else if(line.contains("(")) {
                    int endIndex = line.indexOf("(");
                    int startIndex = 0;
                    for (int i = line.indexOf("("); line.charAt(i) != ' '; --i) {
                      startIndex = i;

                        }

                        if (debug)
                            System.out.println(line.substring(startIndex, endIndex));

                        if(variableMap.get(line.substring(startIndex, endIndex)) == null){
                            variableMap.put(line.substring(startIndex, endIndex), "");
                        }
                }

            }else if(line.contains(";")){
                if(line.contains("=")){
                    int endIndex = line.indexOf("=") -1;
                    int startIndex = 0;
                    for(int i = 0; i < line.indexOf("=") -1; --i){
                        if(line.charAt(i) == ' '){
                            startIndex = i;
                            if(debug)
                                System.out.println(line.substring(startIndex, endIndex));
                            break;
                        }

                  }
                    if(variableMap.get(line.substring(startIndex, endIndex).strip()) == null){
                        variableMap.put(line.substring(startIndex, endIndex).strip(), "");
                    }
                }
            }



        }
for(String key : variableMap.keySet()){
    System.out.println(key + "     val: " + variableMap.get(key));
}
obfuscate(variableMap, file);

        for(String key : variableMap.keySet()){
            System.out.println(key + "     val: " + variableMap.get(key));
        }






    }
    private  static  void obfuscate(HashMap<String, String> varMap, File file) throws IOException {
        genNewNames(varMap);
        Scanner scnr = new Scanner(file);
        BufferedWriter write = new BufferedWriter(new FileWriter("src/ObfuscatedFile"));


        while(scnr.hasNext()){
            String line = scnr.nextLine() + "\n";
            String newLine = "";

            boolean foundMatch = false;
            for(String varName : varMap.keySet()){
                if(line.contains(varName)){
                    foundMatch = true;

                    newLine = line.replace(varName, varMap.get(varName));
                    break;
                }
            }
            if(foundMatch){
                write.write(newLine);
            }else
            {
                write.write(line);
            }
        }

        write.close();

    }

    private static void genNewNames(HashMap<String, String> varMap){

        List<String> wordBank = new LinkedList<>();

        String[] letters = "abcdefghijklmnopqrstuvwxyz".split("");

for(int i =0;i < letters.length; ++i){
    wordBank.add(letters[i]);
}

        int j = 0;
        for(String key : varMap.keySet()){
            varMap.replace(key,wordBank.get(j));
            ++j;
        }
    }
}