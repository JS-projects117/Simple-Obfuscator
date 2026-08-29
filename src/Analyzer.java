import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Decides which declared names may be renamed and assigns the new names.
 *
 * Renaming is name-based: every declaration sharing a name receives the same new
 * name, which keeps overrides, interface implementations and partial classes
 * consistent without full semantic analysis. A name is only renamed when every
 * declaration carrying it passes the policy checks and every use of it binds to
 * something inside the project.
 */
public final class Analyzer {
    private final Model.Project project;
    private final Resolver resolver;
    private final Options options;

    /** names that must not be renamed -> reason */
    public final Map<String, String> keep = new TreeMap<>();
    /** declared names -> new names */
    public final Map<String, String> globalMap = new LinkedHashMap<>();
    /** all declared type and member names */
    public final Set<String> declaredNames = new HashSet<>();
    public final Set<String> declaredTypeNames = new HashSet<>();
    public final Map<String, List<Model.MemberDecl>> membersByName = new HashMap<>();
    public final Map<String, List<Model.TypeDecl>> typesByName = new HashMap<>();
    public final List<String> warnings = new ArrayList<>();
    public int localCount = 0;

    private final Map<Model.TypeDecl, Boolean> unityDerivedCache = new HashMap<>();
    private final Map<Model.TypeDecl, Boolean> externalInterfaceCache = new HashMap<>();
    private final List<Pattern> keepPatterns = new ArrayList<>();

    public Analyzer(Model.Project project, Resolver resolver, Options options) {
        this.project = project;
        this.resolver = resolver;
        this.options = options;
        for (String k : options.keepNames) {
            if (k.contains("*") || k.contains("?")) {
                keepPatterns.add(Pattern.compile(k.replace(".", "\\.").replace("*", ".*").replace("?", ".")));
            }
        }
    }

    public void run() {
        collectDeclarations();
        applyPolicies();
        checkUsages();
        assignNames();
    }

    // ------------------------------------------------------------------ declarations

    private void collectDeclarations() {
        for (Model.TypeDecl t : project.typesByKey.values()) {
            declaredNames.add(t.name);
            declaredTypeNames.add(t.name);
            typesByName.computeIfAbsent(t.name, k -> new ArrayList<>()).add(t);
            for (Model.MemberDecl m : t.members) {
                if (m.kind == Model.MemberKind.CTOR || m.kind == Model.MemberKind.DTOR || m.kind == Model.MemberKind.OPERATOR || m.kind == Model.MemberKind.INDEXER) continue;
                if (m.attributes.contains("DelegateInvoke")) continue;
                declaredNames.add(m.name);
                membersByName.computeIfAbsent(m.name, k -> new ArrayList<>()).add(m);
            }
        }
        for (Model.TypeDecl t : project.typesByKey.values()) {
            for (Model.MemberDecl m : t.members) resolver.inferLocals(m);
        }
    }

    private void keepName(String name, String reason) {
        keep.putIfAbsent(name, reason);
    }

    private boolean userKeeps(String name) {
        if (options.keepNames.contains(name)) return true;
        for (Pattern p : keepPatterns) if (p.matcher(name).matches()) return true;
        return false;
    }

    // ------------------------------------------------------------------ policies

    private void applyPolicies() {
        for (String name : declaredNames) {
            if (userKeeps(name)) keepName(name, "listed in --keep");
            if (Token.CONTEXTUAL_KEYWORDS.contains(name) || Token.KEYWORDS.contains(name)) keepName(name, "keyword");
            if (name.equals("_")) keepName(name, "discard");
            if (!options.noStringProtection && project.stringWords.contains(name)) keepName(name, "appears inside a string literal (possible reflection / Invoke / animation event)");
        }
        for (Model.TypeDecl t : project.typesByKey.values()) {
            String typeReason = typeKeepReason(t);
            if (typeReason != null) keepName(t.name, typeReason);
            for (Model.MemberDecl m : t.members) {
                if (m.kind == Model.MemberKind.CTOR || m.kind == Model.MemberKind.DTOR || m.kind == Model.MemberKind.OPERATOR || m.kind == Model.MemberKind.INDEXER) continue;
                if (m.attributes.contains("DelegateInvoke")) continue;
                String reason = memberKeepReason(t, m);
                if (reason != null) keepName(m.name, reason);
            }
        }
    }

    private String typeKeepReason(Model.TypeDecl t) {
        if (t.name.endsWith("Attribute") || derivesFromExternal(t, "Attribute")) return "attribute class";
        for (String a : t.attributes) {
            if (UnityNames.KEEP_ATTRIBUTES.contains(a)) return "[" + a + "]";
        }
        if (t.kind.equals("delegate") && options.keepDelegates) return "delegate type";
        if (t.isEnum() && options.keepEnums) return "enum (--keep-enums)";
        if (t.modifiers.contains("extern")) return "extern";
        return null;
    }

    private String memberKeepReason(Model.TypeDecl t, Model.MemberDecl m) {
        for (String a : m.attributes) {
            if (UnityNames.KEEP_ATTRIBUTES.contains(a)) return "[" + a + "]";
        }
        if (m.has("extern")) return "extern method";
        if (m.explicitInterfaceImpl) return "explicit interface implementation";
        boolean unity = isUnityDerived(t);
        boolean extIface = implementsExternalInterface(t);

        switch (m.kind) {
            case ENUM_MEMBER:
                if (options.keepEnums) return "enum member (--keep-enums)";
                return null;
            case FIELD: {
                if (m.fieldAttributeSerialized) return "[SerializeField]";
                boolean serializedByUnity = !m.isStatic() && !m.has("readonly") && !m.attributes.contains("NonSerialized");
                if (serializedByUnity && m.isPublic()) {
                    if (unity || isSerializableType(t) || !options.renamePublicFields) return "public field (serialized by Unity / inspector)";
                }
                if (m.isPublic() && m.isStatic() && unity && !options.aggressive) return "public static field on Unity type";
                return null;
            }
            case PROPERTY:
                if (m.fieldAttributeSerialized) return "[field: SerializeField] property";
                if (m.isPublic() && (unity || extIface) && !options.aggressive) return "public property on Unity-derived / interface-implementing type";
                if (m.isPublic() && extIface && options.aggressive && !unity) return "public property on type implementing an external interface";
                if (m.has("override") && !overridesProjectMember(t, m)) return "overrides an external member";
                return null;
            case EVENT:
                if (m.isPublic() && (unity || extIface) && !options.aggressive) return "public event on Unity-derived / interface-implementing type";
                if (m.has("override") && !overridesProjectMember(t, m)) return "overrides an external member";
                return null;
            case METHOD: {
                if (UnityNames.MESSAGES.contains(m.name) && (unity || m.name.equals("Main"))) return "Unity message / entry point";
                if (UnityNames.OBJECT_METHODS.contains(m.name) && (m.isPublic() || m.has("override"))) return "well-known .NET member name";
                if (m.has("override") && !overridesProjectMember(t, m)) return "overrides an external member";
                if (unity && m.name.matches("On[A-Z]\\w*") && !m.isStatic()) return "On* method on Unity type (possible message / callback)";
                if (m.isPublic() && !m.isStatic() && (unity || extIface) && !options.aggressive) return "public method on Unity-derived / interface-implementing type (UnityEvent / animation event / interface)";
                if (m.isPublic() && !m.isStatic() && extIface && options.aggressive) return "public method on type implementing an external interface";
                if (m.isStatic() && m.name.equals("Main")) return "entry point";
                return null;
            }
            default:
                return null;
        }
    }

    private boolean isSerializableType(Model.TypeDecl t) {
        for (String a : t.attributes) if (UnityNames.SERIALIZABLE_TYPE_ATTRIBUTES.contains(a)) return true;
        return false;
    }

    /** True when the type's base chain reaches a type outside the project (class or interface). */
    public boolean isUnityDerived(Model.TypeDecl t) {
        Boolean cached = unityDerivedCache.get(t);
        if (cached != null) return cached;
        unityDerivedCache.put(t, false); // cycle guard
        boolean result = false;
        for (Model.TypeRef b : resolver.resolvedBases(t)) {
            if (b.kind == Model.TypeRef.Kind.PROJECT) {
                if (isUnityDerived(b.decl)) {
                    result = true;
                    break;
                }
            } else if (b.kind != Model.TypeRef.Kind.TYPE_PARAM) {
                result = true;
                break;
            }
        }
        unityDerivedCache.put(t, result);
        return result;
    }

    /** True when the type (or a project base) lists an external interface (I-prefixed) among its bases. */
    private boolean implementsExternalInterface(Model.TypeDecl t) {
        Boolean cached = externalInterfaceCache.get(t);
        if (cached != null) return cached;
        externalInterfaceCache.put(t, false);
        boolean result = false;
        for (Model.TypeRef b : resolver.resolvedBases(t)) {
            if (b.kind == Model.TypeRef.Kind.PROJECT) {
                if (implementsExternalInterface(b.decl)) {
                    result = true;
                    break;
                }
            } else if (b.kind == Model.TypeRef.Kind.EXTERNAL && b.name.length() > 1 && b.name.charAt(0) == 'I' && Character.isUpperCase(b.name.charAt(1))) {
                result = true;
                break;
            }
        }
        externalInterfaceCache.put(t, result);
        return result;
    }

    private boolean derivesFromExternal(Model.TypeDecl t, String externalName) {
        Set<Model.TypeDecl> visited = new HashSet<>();
        return derivesFromExternal(t, externalName, visited);
    }

    private boolean derivesFromExternal(Model.TypeDecl t, String externalName, Set<Model.TypeDecl> visited) {
        if (!visited.add(t)) return false;
        for (Model.TypeRef b : resolver.resolvedBases(t)) {
            if (b.kind == Model.TypeRef.Kind.EXTERNAL && b.name.equals(externalName)) return true;
            if (b.kind == Model.TypeRef.Kind.PROJECT && derivesFromExternal(b.decl, externalName, visited)) return true;
        }
        return false;
    }

    /** Whether an 'override' member overrides something declared (virtual/abstract/override) in a project base. */
    private boolean overridesProjectMember(Model.TypeDecl t, Model.MemberDecl m) {
        Set<Model.TypeDecl> visited = new HashSet<>();
        visited.add(t);
        return findVirtualInBases(t, m.name, visited);
    }

    private boolean findVirtualInBases(Model.TypeDecl t, String name, Set<Model.TypeDecl> visited) {
        for (Model.TypeRef b : resolver.resolvedBases(t)) {
            if (b.kind != Model.TypeRef.Kind.PROJECT || !visited.add(b.decl)) continue;
            for (Model.MemberDecl bm : b.decl.members) {
                if (bm.name.equals(name) && (bm.has("virtual") || bm.has("abstract") || bm.has("override") || b.decl.isInterface())) return true;
            }
            if (findVirtualInBases(b.decl, name, visited)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ usages

    /** Every use of a declared name must bind to a project declaration, otherwise the name is ambiguous. */
    private void checkUsages() {
        for (Model.CsFile f : project.files) {
            for (int i = 0; i < f.sig.size(); i++) {
                Token t = f.tok(i);
                if (!t.isIdent()) continue;
                String name = t.value;
                if (!declaredNames.contains(name) || keep.containsKey(name)) continue;
                if (f.nsCtx[i] || f.namedArg[i]) continue;
                if (i > 0 && f.tok(i - 1).isKeyword("goto")) continue;
                Resolver.Binding b;
                try {
                    b = resolver.bind(f, i);
                } catch (RuntimeException e) {
                    b = Resolver.Binding.UNKNOWN;
                }
                if (b.kind == Resolver.Binding.Kind.LOCAL) continue;
                if (b.kind == Resolver.Binding.Kind.EXTERNAL || b.kind == Resolver.Binding.Kind.UNKNOWN) {
                    keepName(name, "use at " + f.relativePath + ":" + t.line + " does not resolve to a project declaration");
                } else if (b.kind == Resolver.Binding.Kind.NAMESPACE || b.kind == Resolver.Binding.Kind.TYPE_PARAM) {
                    keepName(name, "name also used as a namespace / type parameter at " + f.relativePath + ":" + t.line);
                } else if (f.typeCtx[i] && b.kind == Resolver.Binding.Kind.MEMBER) {
                    keepName(name, "used as a type at " + f.relativePath + ":" + t.line + " but declared as a member");
                }
            }
        }
    }

    // ------------------------------------------------------------------ names

    private void assignNames() {
        Set<String> reserved = new HashSet<>(project.allIdentifiers);
        reserved.addAll(Token.KEYWORDS);
        reserved.addAll(Token.CONTEXTUAL_KEYWORDS);
        NameGen gen = new NameGen(reserved, options.namePrefix);
        List<String> names = new ArrayList<>(declaredNames);
        names.sort(null);
        for (String name : names) {
            if (keep.containsKey(name)) continue;
            globalMap.put(name, gen.next());
        }
        Set<String> globalNew = new HashSet<>(globalMap.values());
        if (!options.noLocals) {
            for (Model.TypeDecl t : project.typesByKey.values()) {
                for (Model.MemberDecl m : t.members) {
                    Set<String> memberReserved = new HashSet<>(reserved);
                    memberReserved.addAll(globalNew);
                    NameGen local = new NameGen(memberReserved, options.namePrefix);
                    for (Model.LocalVar lv : m.locals) {
                        if (lv.name.equals("_") || Token.CONTEXTUAL_KEYWORDS.contains(lv.name)) continue;
                        if (userKeeps(lv.name)) continue;
                        if (lv.isParam && (project.namedArguments.contains(lv.name) || m.attributes.contains("DelegateInvoke"))) continue;
                        if (lv.isParam && m.kind == Model.MemberKind.CTOR && t.kind.equals("record")) continue;
                        lv.newName = local.next();
                        localCount++;
                    }
                }
            }
        }
    }
}
