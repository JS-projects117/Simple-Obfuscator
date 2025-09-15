import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    static final String[] wordsToIgnore = {
            "Math", "Random", "foreach", "switch", "case", "Update", "Start",
            "while", "return", "catch", "Compare", "[", "Awake", "if", "for",
            // Additional Unity lifecycle methods
            "FixedUpdate", "LateUpdate", "OnEnable", "OnDisable", "OnDestroy",
            "OnTriggerEnter", "OnTriggerExit", "OnCollisionEnter", "OnCollisionExit",
            "OnGUI", "OnValidate", "Reset", "OnDrawGizmos", "OnDrawGizmosSelected",
            // System and Unity types
            "MonoBehaviour", "ScriptableObject", "GameObject", "Transform",
            "Vector3", "Vector2", "Quaternion", "string", "int", "float",
            "bool", "void", "object", "List", "Dictionary", "Array",
            "Component", "Rigidbody", "Collider", "Renderer", "Camera"
    };

    // Unity serialization attributes that require name preservation
    static final String[] serializationAttributes = {
            "SerializeField", "SerializeReference", "Header", "Space",
            "Range", "Tooltip", "HideInInspector"
    };

    // Global maps for cross-file consistency
    static HashMap<String, String> globalVariableMap = new HashMap<>();
    static HashMap<String, HashSet<String>> symbolReferences = new HashMap<>();
    static HashMap<String, String> symbolDefinitions = new HashMap<>();
    static HashSet<String> serializedFields = new HashSet<>();
    static HashSet<String> overrideMethods = new HashSet<>();
    static HashMap<String, FileAnalysis> fileAnalyses = new HashMap<>();

    static class FileAnalysis {
        HashSet<String> definedSymbols = new HashSet<>();
        HashSet<String> referencedSymbols = new HashSet<>();
        String content;
        String originalPath;
    }

    public static void main(String[] args) throws IOException {
        System.out.println("=== Phase 1: Finding Files ===");
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

        System.out.println("Found " + files.length + " files");

        System.out.println("\n=== Phase 2: Analyzing All Files ===");
        analyzeAllFiles(files);

        System.out.println("\n=== Phase 3: Loading Variables (Enhanced) ===");
        loadVariables(globalVariableMap, files);

        System.out.println("\n=== Phase 4: Building Cross-References ===");
        buildCrossReferences();

        System.out.println("\nBefore obfuscation:");
        for (String key : globalVariableMap.keySet()) {
            System.out.println(key + "     val: " + globalVariableMap.get(key));
        }

        System.out.println("\n=== Phase 5: Applying Obfuscation ===");
        obfuscate(globalVariableMap, files);

        System.out.println("\nAfter obfuscation:");
        for (String key : globalVariableMap.keySet()) {
            System.out.println(key + "     val: " + globalVariableMap.get(key));
        }

        printStatistics();
    }

    private static void findAllFiles(LinkedList<File> fileList, String path) {
        File[] filesInDir = new File(path).listFiles();
        if (filesInDir != null) {
            for (File x : filesInDir) {
                if (x.isDirectory()) {
                    findAllFiles(fileList, x.getPath());
                } else if (x.getName().endsWith(".cs")) {
                    fileList.add(x);
                }
            }
        }
    }

    private static void analyzeAllFiles(File[] files) throws IOException {
        for (File file : files) {
            System.out.println("Analyzing: " + file.getName());
            FileAnalysis analysis = analyzeFile(file);
            fileAnalyses.put(file.getAbsolutePath(), analysis);
        }
    }

    private static FileAnalysis analyzeFile(File file) throws IOException {
        FileAnalysis analysis = new FileAnalysis();
        analysis.originalPath = file.getAbsolutePath();

        Scanner scnr = new Scanner(file);
        StringBuilder contentBuilder = new StringBuilder();
        while (scnr.hasNextLine()) {
            contentBuilder.append(scnr.nextLine()).append("\n");
        }
        scnr.close();

        analysis.content = contentBuilder.toString();
        String contentWithoutComments = removeCommentsAndStrings(analysis.content);

        findDefinedSymbols(contentWithoutComments, analysis);
        findReferencedSymbols(analysis.content, analysis);
        findSerializedFields(analysis.content);

        return analysis;
    }

    private static void findDefinedSymbols(String content, FileAnalysis analysis) {
        Pattern classPattern = Pattern.compile(
                "(?:public|private|protected|internal)?\\s*(?:abstract|sealed|static)?\\s*class\\s+" +
                        "([a-zA-Z_]\\w*)(?:\\s*:\\s*[\\w\\s,<>]+)?\\s*\\{"
        );
        Matcher classMatcher = classPattern.matcher(content);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            if (!shouldIgnore(className)) {
                analysis.definedSymbols.add(className);
                symbolDefinitions.put(className, analysis.originalPath);
            }
        }

        // Enhanced method detection with override/virtual/abstract checks
        Pattern methodPattern = Pattern.compile(
                "(?:public|private|protected|internal|static)?\\s*(virtual|override|abstract|async)?\\s*" +
                        "(?:void|int|float|bool|string|[A-Z]\\w*(?:<[^>]+>)?)\\s+([a-zA-Z_]\\w*)\\s*\\([^)]*\\)"
        );
        Matcher methodMatcher = methodPattern.matcher(content);
        while (methodMatcher.find()) {
            String modifier = methodMatcher.group(1);
            String methodName = methodMatcher.group(2);

            if (!shouldIgnore(methodName) && !isConstructorName(methodName, analysis.definedSymbols)) {
                analysis.definedSymbols.add(methodName);
                symbolDefinitions.put(methodName, analysis.originalPath);

                if (modifier != null && (modifier.equals("override") || modifier.equals("virtual") || modifier.equals("abstract"))) {
                    overrideMethods.add(methodName);
                }
            }
        }

        Pattern fieldPattern = Pattern.compile(
                "(?:public|private|protected|internal|static)?\\s*(?:readonly|const)?\\s*" +
                        "(?:[A-Z]\\w*(?:<[^>]+>)?)\\s+([a-zA-Z_]\\w*)\\s*[;=]"
        );
        Matcher fieldMatcher = fieldPattern.matcher(content);
        while (fieldMatcher.find()) {
            String fieldName = fieldMatcher.group(1);
            if (!shouldIgnore(fieldName) && !serializedFields.contains(fieldName)) {
                analysis.definedSymbols.add(fieldName);
                symbolDefinitions.put(fieldName, analysis.originalPath);
            }
        }
    }

    private static void findReferencedSymbols(String content, FileAnalysis analysis) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) {
                continue;
            }

            Pattern methodCallPattern = Pattern.compile("([a-zA-Z_]\\w*)\\s*\\(");
            Matcher matcher = methodCallPattern.matcher(line);
            while (matcher.find()) {
                String referencedSymbol = matcher.group(1);
                if (!shouldIgnore(referencedSymbol)) {
                    analysis.referencedSymbols.add(referencedSymbol);
                }
            }

            Pattern fieldAccessPattern = Pattern.compile("(?:\\.|^|\\s)([a-zA-Z_]\\w*)(?:\\s*[=;]|\\s+)");
            matcher = fieldAccessPattern.matcher(line);
            while (matcher.find()) {
                String referencedSymbol = matcher.group(1);
                if (!shouldIgnore(referencedSymbol)) {
                    analysis.referencedSymbols.add(referencedSymbol);
                }
            }

            Pattern typePattern = Pattern.compile("\\bnew\\s+([a-zA-Z_]\\w*)\\s*\\(");
            matcher = typePattern.matcher(line);
            while (matcher.find()) {
                String referencedSymbol = matcher.group(1);
                if (!shouldIgnore(referencedSymbol)) {
                    analysis.referencedSymbols.add(referencedSymbol);
                }
            }
        }
    }

    private static void findSerializedFields(String content) {
        String[] lines = content.split("\n");
        boolean nextFieldIsSerialized = false;

        for (String line : lines) {
            line = line.trim();

            for (String attr : serializationAttributes) {
                if (line.contains("[" + attr)) {
                    nextFieldIsSerialized = true;
                    break;
                }
            }

            if (nextFieldIsSerialized && (line.contains("public") || line.contains("private"))) {
                Pattern fieldPattern = Pattern.compile("(?:public|private)\\s+(?:\\w+)\\s+(\\w+)");
                Matcher matcher = fieldPattern.matcher(line);
                if (matcher.find()) {
                    String fieldName = matcher.group(1);
                    serializedFields.add(fieldName);
                }
                nextFieldIsSerialized = false;
            }

            if (line.startsWith("public ") && !line.contains("static") && !line.contains("const")) {
                Pattern fieldPattern = Pattern.compile("public\\s+(?:\\w+)\\s+(\\w+)");
                Matcher matcher = fieldPattern.matcher(line);
                if (matcher.find()) {
                    String fieldName = matcher.group(1);
                    serializedFields.add(fieldName);
                }
            }
        }
    }

    private static void buildCrossReferences() {
        for (FileAnalysis analysis : fileAnalyses.values()) {
            for (String referencedSymbol : analysis.referencedSymbols) {
                if (!symbolReferences.containsKey(referencedSymbol)) {
                    symbolReferences.put(referencedSymbol, new HashSet<>());
                }
                symbolReferences.get(referencedSymbol).add(analysis.originalPath);
            }
        }

        for (FileAnalysis analysis : fileAnalyses.values()) {
            for (String definedSymbol : analysis.definedSymbols) {
                if (!globalVariableMap.containsKey(definedSymbol)) {
                    globalVariableMap.put(definedSymbol, "");
                }
            }
        }
    }

    private static void obfuscate(HashMap<String, String> varMap, File[] files) throws IOException {
        genNewNames(varMap);

        String inputRoot = Paths.get("src/filesForObfuscation").toAbsolutePath().toString();
        String outputRoot = Paths.get("src/ObfuscatedFiles").toAbsolutePath().toString();

        for (File file : files) {
            Scanner scnr = new Scanner(file);
            StringBuilder content = new StringBuilder();
            while (scnr.hasNextLine()) {
                content.append(scnr.nextLine()).append("\n");
            }
            scnr.close();

            String fileContent = content.toString();
            String obfuscatedContent = applyObfuscation(fileContent, varMap);

            String relativePath = Paths.get(inputRoot).relativize(Paths.get(file.getAbsolutePath())).toString();
            File outFile = new File(outputRoot, relativePath);

            outFile.getParentFile().mkdirs();

            BufferedWriter write = new BufferedWriter(new FileWriter(outFile));
            write.write(obfuscatedContent);
            write.close();

            System.out.println("Obfuscated: " + relativePath);
        }
    }

    private static String applyObfuscation(String content, HashMap<String, String> varMap) {
        List<Map.Entry<String, String>> sortedEntries = new ArrayList<>(varMap.entrySet());
        sortedEntries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));

        for (Map.Entry<String, String> entry : sortedEntries) {
            String original = entry.getKey();
            String obfuscated = entry.getValue();

            if (!obfuscated.isEmpty()) {
                String regex = "\\b" + Pattern.quote(original) + "\\b";
                content = content.replaceAll(regex, obfuscated);
            }
        }

        return content;
    }

    private static void genNewNames(HashMap<String, String> varMap) {
        List<String> wordBank = new LinkedList<>();
        String[] letters = "abcdefghijklmnopqrstuvwxyz".split("");

        for (int i = 0; i < letters.length; ++i) {
            wordBank.add(letters[i]);
        }

        for (String letter1 : letters) {
            for (String letter2 : letters) {
                wordBank.add(letter1 + letter2);
            }
        }

        String[] keySet = varMap.keySet().toArray(new String[0]);
        for (String key : keySet) {
            if (shouldRemoveFromObfuscation(key)) {
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

    private static boolean shouldRemoveFromObfuscation(String key) {
        for (String word : wordsToIgnore) {
            if (key.contains(word)) {
                return true;
            }
        }

        if (key.contains(".")) {
            return true;
        }

        if (key.contains("<") && !key.contains("T")) {
            return true;
        }

        if (serializedFields.contains(key)) {
            return true;
        }

        if (overrideMethods.contains(key)) {
            return true; // skip overridden/virtual/abstract methods
        }

        if (key.length() <= 1) {
            return true;
        }

        return false;
    }

    private static String GenerateWord(String word, List<String> wordBank, int index) {
        int bankLen = wordBank.size();

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
                    } else if (line.substring(startIndex).contains(":")) {
                        endIndex = line.substring(startIndex).indexOf(':') + startIndex;
                    } else {
                        endIndex = line.substring(startIndex).length() + startIndex;
                    }

                    if (debug)
                        System.out.println(line.substring(startIndex, endIndex));
                    try {
                        String className = line.substring(startIndex, endIndex).trim();
                        if (!shouldIgnore(className)) {
                            variableMap.put(className, "");
                        }
                    } catch (Exception e) {
                        System.out.println(line);
                        System.out.println(e);
                    }
                } else if (line.contains("(") && !line.trim().startsWith("//")) {
                    int endIndex = line.indexOf("(");

                    while (endIndex > 0 && (line.charAt(endIndex - 1) == ' ' || line.charAt(endIndex) == ' ')) {
                        endIndex -= 1;
                    }
                    int startIndex = 0;

                    for (int i = endIndex - 1; i >= 0 && line.charAt(i) != ' '; --i) {
                        startIndex = i;
                    }

                    if (line.charAt(endIndex) != ' ' && line.charAt(endIndex) != '(')
                        ++endIndex;

                    try {
                        String methodName = line.substring(startIndex, endIndex).trim();
                        if (variableMap.get(methodName) == null && !shouldIgnore(methodName)) {
                            variableMap.put(methodName, "");
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                } else if (line.contains(";") && !line.contains("import") && !line.contains("using") && !line.trim().startsWith("//")) {
                    if (line.contains("=")) {
                        int endIndex = line.indexOf("=");

                        while (endIndex > 0 && line.charAt(endIndex - 1) == ' ') {
                            endIndex -= 1;
                        }

                        int startIndex = 0;
                        for (int i = endIndex - 1; i >= 0 && line.charAt(i) != ' '; --i) {
                            startIndex = i;
                        }

                        try {
                            String varName = line.substring(startIndex, endIndex).trim();
                            if (variableMap.get(varName) == null && !shouldIgnore(varName)) {
                                variableMap.put(varName, "");
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
            }
            scnr.close();
        }
    }

    private static String removeCommentsAndStrings(String content) {
        content = content.replaceAll("//.*", "");
        content = content.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        content = content.replaceAll("\"([^\"\\\\]|\\\\.)*\"", "\"\"");
        content = content.replaceAll("'([^'\\\\]|\\\\.)*'", "''");
        return content;
    }

    private static boolean shouldIgnore(String name) {
        for (String word : wordsToIgnore) {
            if (name.equals(word) || name.contains(word)) {
                return true;
            }
        }

        // Additional protection for Unity event system properties
        if (name.matches("On[A-Z][a-zA-Z]*") || // OnSomething
                name.equals("Execute") ||
                name.equals("OnExecute")) {
            return true;
        }

        return name.length() <= 1 ||
                Character.isLowerCase(name.charAt(0)) ||
                serializedFields.contains(name) ||
                overrideMethods.contains(name);
    }

    private static boolean isConstructorName(String methodName, HashSet<String> definedClasses) {
        return definedClasses.contains(methodName);
    }

    private static void printStatistics() {
        System.out.println("\n=== Obfuscation Statistics ===");
        System.out.println("Files processed: " + fileAnalyses.size());
        System.out.println("Symbols obfuscated: " + globalVariableMap.size());
        System.out.println("Serialized fields protected: " + serializedFields.size());
        System.out.println("Override/virtual/abstract methods protected: " + overrideMethods.size());
    }
}
