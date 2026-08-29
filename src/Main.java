import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Simple-Obfuscator entry point: renames identifiers in the C# scripts of a Unity
 * project to short meaningless names while preserving everything Unity resolves by
 * name. See {@link Options#USAGE}.
 */
public final class Main {

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.println(Options.USAGE);
            System.exit(2);
            return;
        }
        int code = run(options, System.out);
        System.exit(code);
    }

    public static int run(Options options, PrintStream out) throws IOException {
        Path input = Paths.get(options.inputDir).toAbsolutePath().normalize();
        Path output = Paths.get(options.outputDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(input)) {
            out.println("error: input folder does not exist: " + input);
            return 2;
        }
        if (output.startsWith(input) && !options.dryRun) {
            out.println("error: output folder must not be inside the input folder");
            return 2;
        }

        // ---- Phase 1: find and parse files
        List<Path> csFiles;
        List<Path> otherFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(input)) {
            List<Path> all = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(all::add);
            all.sort(Comparator.comparing(Path::toString));
            csFiles = new ArrayList<>();
            for (Path p : all) {
                if (p.toString().endsWith(".cs")) csFiles.add(p);
                else otherFiles.add(p);
            }
        }
        out.println("Simple-Obfuscator");
        out.println("  input : " + input);
        out.println("  output: " + output + (options.dryRun ? " (dry run)" : ""));
        out.println("  found " + csFiles.size() + " C# file(s)");

        Model.Project project = new Model.Project();
        int parseErrors = 0;
        for (Path p : csFiles) {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            String rel = input.relativize(p).toString().replace('\\', '/');
            Model.CsFile f = new Model.CsFile(p.toString(), rel, Lexer.tokenize(src));
            project.files.add(f);
            try {
                Parser.parse(project, f);
            } catch (Parser.ParseException e) {
                out.println("  parse error: " + e.getMessage());
                parseErrors++;
            } catch (RuntimeException e) {
                out.println("  parse error in " + rel + ": " + e);
                parseErrors++;
            }
        }
        if (parseErrors > 0) {
            out.println("error: " + parseErrors + " file(s) could not be parsed; nothing was written. "
                    + "Renaming with an incomplete picture of the project would produce code that does not compile.");
            return 1;
        }
        int typeCount = project.typesByKey.size();
        int memberCount = 0;
        for (Model.TypeDecl t : project.typesByKey.values()) memberCount += t.members.size();
        out.println("  parsed " + typeCount + " type(s), " + memberCount + " member(s)");

        // ---- Phase 2: analyse
        Resolver resolver = new Resolver(project);
        Analyzer analyzer = new Analyzer(project, resolver, options);
        analyzer.run();

        int typesRenamed = 0, typesKept = 0, membersRenamed = 0, membersKept = 0;
        for (String name : analyzer.declaredTypeNames) {
            if (analyzer.globalMap.containsKey(name)) typesRenamed++;
            else typesKept++;
        }
        for (Map.Entry<String, List<Model.MemberDecl>> e : analyzer.membersByName.entrySet()) {
            if (analyzer.globalMap.containsKey(e.getKey())) membersRenamed += e.getValue().size();
            else membersKept += e.getValue().size();
        }
        out.println("  type names    : " + typesRenamed + " renamed, " + typesKept + " kept");
        out.println("  member names  : " + membersRenamed + " renamed, " + membersKept + " kept");
        out.println("  locals/params : " + analyzer.localCount + " renamed");

        if (options.verbose) {
            out.println();
            out.println("Kept names:");
            for (Map.Entry<String, String> e : analyzer.keep.entrySet()) {
                if (analyzer.declaredNames.contains(e.getKey())) out.println("  " + e.getKey() + "  <- " + e.getValue());
            }
            out.println();
            out.println("Renamed:");
            for (Map.Entry<String, String> e : analyzer.globalMap.entrySet()) {
                out.println("  " + e.getKey() + " -> " + e.getValue());
            }
        }

        // ---- Phase 3: rewrite
        Map<Model.CsFile, String> outputs = new HashMap<>();
        Map<Model.CsFile, String> outputNames = new HashMap<>(); // relative output path
        for (Model.CsFile f : project.files) {
            rewrite(f, resolver, analyzer, options);
            outputs.put(f, render(f, options));
            outputNames.put(f, outputPathFor(f, analyzer));
        }

        if (options.dryRun) {
            out.println();
            out.println("Dry run: no files written.");
            for (Model.CsFile f : project.files) {
                String o = outputNames.get(f);
                if (!o.equals(f.relativePath)) out.println("  " + f.relativePath + " -> " + o);
            }
            return 0;
        }

        // ---- Phase 4: write
        if (options.clean && Files.exists(output)) {
            deleteRecursively(output);
        }
        Files.createDirectories(output);
        Set<String> handledMeta = new HashSet<>();
        int written = 0;
        for (Model.CsFile f : project.files) {
            String rel = outputNames.get(f);
            Path target = output.resolve(rel);
            Files.createDirectories(target.getParent());
            Files.writeString(target, outputs.get(f), StandardCharsets.UTF_8);
            written++;
            // carry the .meta file (keeps the script GUID so scene references survive)
            Path meta = input.resolve(f.relativePath + ".meta");
            if (Files.isRegularFile(meta)) {
                handledMeta.add(f.relativePath + ".meta");
                if (options.copyOthers) {
                    Path metaTarget = output.resolve(rel + ".meta");
                    Files.copy(meta, metaTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!rel.equals(f.relativePath)) out.println("  renamed file " + f.relativePath + " -> " + rel);
        }
        int copied = 0;
        if (options.copyOthers) {
            for (Path p : otherFiles) {
                String rel = input.relativize(p).toString().replace('\\', '/');
                if (handledMeta.contains(rel)) continue;
                Path target = output.resolve(rel);
                Files.createDirectories(target.getParent());
                Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        out.println("  wrote " + written + " C# file(s), copied " + copied + " other file(s)");

        if (!"none".equals(options.mapFile)) {
            Path mapPath = options.mapFile == null ? output.resolve("obfuscation-map.txt") : Paths.get(options.mapFile);
            Files.writeString(mapPath, buildMap(project, analyzer), StandardCharsets.UTF_8);
            out.println("  rename map: " + mapPath);
        }
        out.println("Done.");
        return 0;
    }

    // ------------------------------------------------------------------ rewriting

    private static void rewrite(Model.CsFile f, Resolver resolver, Analyzer analyzer, Options options) {
        for (int i = 0; i < f.sig.size(); i++) {
            Token t = f.tok(i);
            if (!t.isIdent()) continue;
            if (f.nsCtx[i] || f.namedArg[i]) continue;
            if (Token.CONTEXTUAL_KEYWORDS.contains(t.value)) continue;
            if (i > 0 && f.tok(i - 1).isKeyword("goto")) continue;
            Resolver.Binding b;
            try {
                b = resolver.bind(f, i);
            } catch (RuntimeException e) {
                continue;
            }
            switch (b.kind) {
                case LOCAL:
                    if (b.local.newName != null) t.text = b.local.newName;
                    break;
                case MEMBER:
                case TYPE: {
                    String n = analyzer.globalMap.get(t.value);
                    if (n != null) t.text = n;
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static String render(Model.CsFile f, Options options) {
        StringBuilder sb = new StringBuilder();
        for (Token t : f.all) {
            if (t.kind == Token.Kind.COMMENT && !options.keepComments) {
                sb.append(' ');
                continue;
            }
            if (t.kind == Token.Kind.EOF) break;
            sb.append(t.text);
        }
        return sb.toString();
    }

    /** Output path: rename the file when it is named after a renamed type it declares. */
    private static String outputPathFor(Model.CsFile f, Analyzer analyzer) {
        String rel = f.relativePath;
        int slash = rel.lastIndexOf('/');
        String dir = slash < 0 ? "" : rel.substring(0, slash + 1);
        String base = rel.substring(slash + 1, rel.length() - 3); // strip .cs
        for (Model.TypeDecl t : f.types) {
            String n = analyzer.globalMap.get(t.name);
            if (n == null) continue;
            if (base.equals(t.name)) return dir + n + ".cs";
        }
        for (Model.TypeDecl t : f.types) {
            String n = analyzer.globalMap.get(t.name);
            if (n == null) continue;
            if (base.startsWith(t.name + ".")) return dir + n + base.substring(t.name.length()) + ".cs";
        }
        return rel;
    }

    private static String buildMap(Model.Project project, Analyzer analyzer) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Simple-Obfuscator rename map\n");
        sb.append("# kind\toriginal\tobfuscated\n");
        List<Model.TypeDecl> types = new ArrayList<>(project.typesByKey.values());
        types.sort(Comparator.comparing(Model.TypeDecl::fullName));
        for (Model.TypeDecl t : types) {
            String n = analyzer.globalMap.get(t.name);
            sb.append(n != null ? "type" : "type-kept").append('\t').append(t.fullName()).append('\t').append(n != null ? n : t.name).append('\n');
            List<Model.MemberDecl> members = new ArrayList<>(t.members);
            members.sort(Comparator.comparing(m -> m.name));
            for (Model.MemberDecl m : members) {
                if (m.kind == Model.MemberKind.CTOR || m.kind == Model.MemberKind.DTOR || m.kind == Model.MemberKind.OPERATOR || m.kind == Model.MemberKind.INDEXER) continue;
                if (m.attributes.contains("DelegateInvoke")) continue;
                String mn = analyzer.globalMap.get(m.name);
                sb.append(mn != null ? "member" : "member-kept").append('\t').append(t.fullName()).append('.').append(m.name)
                        .append('\t').append(mn != null ? mn : m.name).append('\n');
                for (Model.LocalVar lv : m.locals) {
                    if (lv.newName != null) {
                        sb.append("local\t").append(t.fullName()).append('.').append(m.name).append("()/").append(lv.name)
                                .append('\t').append(lv.newName).append('\n');
                    }
                }
            }
        }
        sb.append("\n# kept names and why\n");
        for (Map.Entry<String, String> e : analyzer.keep.entrySet()) {
            if (analyzer.declaredNames.contains(e.getKey())) sb.append("# ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            all.sort(Comparator.reverseOrder());
            for (Path p : all) Files.delete(p);
        }
    }
}
