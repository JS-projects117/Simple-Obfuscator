import java.util.ArrayList;
import java.util.List;

/** Command line options. */
public final class Options {
    public String inputDir = "src/filesForObfuscation";
    public String outputDir = "src/ObfuscatedFiles";
    public final List<String> keepNames = new ArrayList<>();
    public boolean aggressive = false;
    public boolean renamePublicFields = false;
    public boolean keepEnums = false;
    public boolean keepDelegates = false;
    public boolean noLocals = false;
    public boolean keepComments = false;
    public boolean noStringProtection = false;
    public boolean dryRun = false;
    public boolean verbose = false;
    public boolean clean = false;
    public boolean copyOthers = true;
    public String mapFile = null;   // null = <output>/obfuscation-map.txt, "none" = do not write
    public String namePrefix = "";

    public static final String USAGE = String.join("\n",
            "Usage: java -cp out Main [<inputDir> [<outputDir>]] [options]",
            "",
            "Obfuscates the C# scripts of a Unity project by renaming types, members, parameters and locals",
            "to short meaningless names, while keeping every name that Unity, the inspector or reflection",
            "needs (serialized fields, message methods, UnityEvent targets, string-referenced names, ...).",
            "",
            "  <inputDir>            Folder containing .cs files (default: src/filesForObfuscation)",
            "  <outputDir>           Folder to write the obfuscated copy to (default: src/ObfuscatedFiles)",
            "",
            "Options:",
            "  --keep <names>        Comma separated names (or globs like On*) that must not be renamed",
            "  --keep-file <file>    File with one name per line that must not be renamed",
            "  --aggressive          Also rename public methods/properties/events of MonoBehaviour-derived types",
            "                        (breaks UnityEvent / Button.onClick / animation-event bindings to them)",
            "  --rename-public-fields  Rename public fields of plain (non-Unity, non-[Serializable]) classes",
            "  --keep-enums          Do not rename enum types and enum members",
            "  --keep-delegates      Do not rename delegate types",
            "  --no-locals           Do not rename parameters and local variables",
            "  --keep-comments       Keep comments (they are stripped by default)",
            "  --no-string-protection  Rename names even when they appear inside string literals",
            "  --prefix <p>          Prefix for generated names (default: none)",
            "  --map <file>          Where to write the rename map (default: <outputDir>/obfuscation-map.txt, 'none' to skip)",
            "  --clean               Delete the output folder before writing",
            "  --no-copy             Do not copy non-.cs files (.meta, assets) to the output folder",
            "  --dry-run             Analyse and print the plan without writing anything",
            "  --verbose             Print details about what was kept and why",
            "  --help                Show this help");

    public static Options parse(String[] args) {
        Options o = new Options();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--keep":
                    for (String n : requireValue(args, ++i, a).split(",")) if (!n.trim().isEmpty()) o.keepNames.add(n.trim());
                    break;
                case "--keep-file":
                    try {
                        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(requireValue(args, ++i, a)))) {
                            String n = line.trim();
                            if (!n.isEmpty() && !n.startsWith("#")) o.keepNames.add(n);
                        }
                    } catch (java.io.IOException e) {
                        throw new IllegalArgumentException("cannot read keep file: " + e.getMessage());
                    }
                    break;
                case "--aggressive": o.aggressive = true; break;
                case "--rename-public-fields": o.renamePublicFields = true; break;
                case "--keep-enums": o.keepEnums = true; break;
                case "--keep-delegates": o.keepDelegates = true; break;
                case "--no-locals": o.noLocals = true; break;
                case "--keep-comments": o.keepComments = true; break;
                case "--no-string-protection": o.noStringProtection = true; break;
                case "--prefix": o.namePrefix = requireValue(args, ++i, a); break;
                case "--map": o.mapFile = requireValue(args, ++i, a); break;
                case "--clean": o.clean = true; break;
                case "--no-copy": o.copyOthers = false; break;
                case "--dry-run": o.dryRun = true; break;
                case "--verbose": case "-v": o.verbose = true; break;
                case "--help": case "-h":
                    System.out.println(USAGE);
                    System.exit(0);
                    break;
                default:
                    if (a.startsWith("--")) throw new IllegalArgumentException("unknown option: " + a);
                    positional.add(a);
            }
        }
        if (positional.size() > 2) throw new IllegalArgumentException("too many arguments");
        if (positional.size() >= 1) o.inputDir = positional.get(0);
        if (positional.size() >= 2) o.outputDir = positional.get(1);
        return o;
    }

    private static String requireValue(String[] args, int i, String opt) {
        if (i >= args.length) throw new IllegalArgumentException(opt + " requires a value");
        return args[i];
    }
}
