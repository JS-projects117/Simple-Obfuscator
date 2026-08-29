import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Declaration model produced by {@link Parser} and consumed by the resolver / analyzer. */
public final class Model {
    private Model() {}

    /** A parsed C# source file. */
    public static final class CsFile {
        public final String path;          // absolute path
        public final String relativePath;  // relative to input root
        public final List<Token> all;      // every token incl. trivia
        public final List<Token> sig;      // significant tokens (no trivia), sig.get(i).index == i
        public int[] match;                // matching bracket index for openers/closers, -1 otherwise
        public int[] genericMatch;         // matching '<' / '>' index when the pair is a generic arg list
        public int[] enclosing;            // innermost unclosed opener before each token, -1 at top level
        public boolean[] namedArg;         // identifier is a named-argument name or a label (never renamed)
        public boolean[] initBrace;        // '{' opens an object / collection initializer
        public boolean[] typeCtx;          // token is in a type position
        public boolean[] nsCtx;            // token is a namespace / using-directive segment (never renamed)
        public TypeDecl[] typeOf;          // enclosing type declaration per token (null at file level)
        public MemberDecl[] memberOf;      // enclosing member declaration per token (null outside members)
        public final List<String> usings = new ArrayList<>();          // imported namespaces
        public final List<int[]> usingStatics = new ArrayList<>();     // [start,end] token ranges of the type name
        public final Map<String, int[]> aliases = new HashMap<>();     // alias name -> [start,end] range
        public final List<TypeDecl> types = new ArrayList<>();         // top-level types declared in this file

        public CsFile(String path, String relativePath, List<Token> all) {
            this.path = path;
            this.relativePath = relativePath;
            this.all = all;
            this.sig = new ArrayList<>();
            for (Token t : all) {
                if (!t.isTrivia()) {
                    t.index = sig.size();
                    sig.add(t);
                }
            }
        }

        public Token tok(int i) {
            return sig.get(i);
        }
    }

    /** A class / struct / interface / enum / record / delegate declaration (partials are merged). */
    public static final class TypeDecl {
        public String name;
        public String kind;                       // class, struct, interface, enum, record, delegate
        public String namespace = "";             // enclosing namespace ("" for global)
        public TypeDecl outer;                    // enclosing type for nested types
        public final List<String> typeParams = new ArrayList<>();
        public final Map<String, List<TypeRange>> constraints = new HashMap<>();
        public final List<TypeRange> bases = new ArrayList<>();
        public final List<MemberDecl> members = new ArrayList<>();
        public final List<TypeDecl> nested = new ArrayList<>();
        public final Set<String> modifiers = new HashSet<>();
        public final Set<String> attributes = new HashSet<>();
        public final List<DeclSite> sites = new ArrayList<>();   // one per (partial) declaration

        public String fullName() {
            String prefix = outer != null ? outer.fullName() : namespace;
            return prefix.isEmpty() ? name : prefix + "." + name;
        }

        public String key() {
            return fullName() + "`" + typeParams.size();
        }

        public boolean isEnum() {
            return kind.equals("enum");
        }

        public boolean isInterface() {
            return kind.equals("interface");
        }

        /** Enclosing namespace of the outermost type. */
        public String rootNamespace() {
            TypeDecl t = this;
            while (t.outer != null) t = t.outer;
            return t.namespace;
        }

        @Override
        public String toString() {
            return kind + " " + fullName();
        }
    }

    /** Where a (possibly partial) type is declared. */
    public static final class DeclSite {
        public final CsFile file;
        public final int nameTok;

        public DeclSite(CsFile file, int nameTok) {
            this.file = file;
            this.nameTok = nameTok;
        }
    }

    public enum MemberKind { FIELD, PROPERTY, METHOD, CTOR, EVENT, ENUM_MEMBER, INDEXER, OPERATOR, DTOR }

    /** A member of a type. */
    public static final class MemberDecl {
        public String name;
        public MemberKind kind;
        public TypeDecl owner;
        public CsFile file;
        public int nameTok = -1;
        public TypeRange type;                        // declared type (field/property/event) or return type (method)
        public final List<Param> params = new ArrayList<>();
        public boolean hasParamsArray;
        public final List<String> typeParams = new ArrayList<>();
        public final Map<String, List<TypeRange>> constraints = new HashMap<>();
        public final Set<String> modifiers = new HashSet<>();
        public final Set<String> attributes = new HashSet<>();
        public boolean fieldAttributeSerialized;      // [field: SerializeField] on a property
        public int rangeStart, rangeEnd;              // whole member token range (inclusive)
        public final List<int[]> scopeRanges = new ArrayList<>();  // parameter list / bodies where locals live
        public final List<LocalVar> locals = new ArrayList<>();
        public boolean explicitInterfaceImpl;         // void IFoo.Bar()

        public boolean isStatic() {
            return modifiers.contains("static") || modifiers.contains("const");
        }

        public boolean isPublic() {
            return modifiers.contains("public");
        }

        public boolean has(String mod) {
            return modifiers.contains(mod);
        }

        @Override
        public String toString() {
            return kind + " " + (owner != null ? owner.fullName() : "?") + "." + name;
        }
    }

    public static final class Param {
        public String name;
        public int nameTok;
        public TypeRange type;
    }

    /** Range of significant tokens [start, end] (inclusive) in a file that spell a type. */
    public static final class TypeRange {
        public final CsFile file;
        public final int start, end;

        public TypeRange(CsFile file, int start, int end) {
            this.file = file;
            this.start = start;
            this.end = end;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (int i = start; i <= end; i++) sb.append(file.tok(i).text);
            return sb.toString();
        }

        @Override
        public String toString() {
            return text();
        }
    }

    /** A local variable, parameter, lambda parameter, pattern variable or local function. */
    public static final class LocalVar {
        public String name;
        public int declTok;          // token index of the declaring identifier
        public int scopeStart;       // first token index where the name is visible
        public int scopeEnd;         // last token index where the name is visible
        public TypeRef type;         // resolved type (may be UNKNOWN)
        public TypeRange typeRange;  // declared type tokens, null for var / lambda params
        public int[] initRange;      // initializer expression tokens for 'var', or foreach source expression
        public boolean isForeach;    // initRange is the collection expression of a foreach
        public boolean isLocalFunction;
        public boolean isParam;
        public String newName;
    }

    /** A resolved type reference. */
    public static final class TypeRef {
        public enum Kind { PROJECT, EXTERNAL, UNKNOWN, TYPE_PARAM, NAMESPACE }

        public final Kind kind;
        public final TypeDecl decl;          // PROJECT
        public final String name;            // EXTERNAL simple name / NAMESPACE full name / TYPE_PARAM name
        public final List<TypeRef> args;     // generic arguments
        public final int arrayRank;          // >0 for arrays
        public final TypeRef constraint;     // TYPE_PARAM: first resolved constraint, may be null

        public static final TypeRef UNKNOWN = new TypeRef(Kind.UNKNOWN, null, "?", List.of(), 0, null);

        public TypeRef(Kind kind, TypeDecl decl, String name, List<TypeRef> args, int arrayRank, TypeRef constraint) {
            this.kind = kind;
            this.decl = decl;
            this.name = name;
            this.args = args;
            this.arrayRank = arrayRank;
            this.constraint = constraint;
        }

        public static TypeRef project(TypeDecl d, List<TypeRef> args) {
            return new TypeRef(Kind.PROJECT, d, d.name, args, 0, null);
        }

        public static TypeRef external(String name, List<TypeRef> args) {
            return new TypeRef(Kind.EXTERNAL, null, name, args, 0, null);
        }

        public static TypeRef namespace(String name) {
            return new TypeRef(Kind.NAMESPACE, null, name, List.of(), 0, null);
        }

        public static TypeRef typeParam(String name, TypeRef constraint) {
            return new TypeRef(Kind.TYPE_PARAM, null, name, List.of(), 0, constraint);
        }

        public TypeRef withArrayRank(int rank) {
            return new TypeRef(kind, decl, name, args, rank, constraint);
        }

        public boolean isArray() {
            return arrayRank > 0;
        }

        public TypeRef elementType() {
            return arrayRank > 1 ? withArrayRank(arrayRank - 1) : withArrayRank(0);
        }

        public boolean isProject() {
            return kind == Kind.PROJECT && arrayRank == 0;
        }

        /** The type whose members are accessed through this reference (constraint for type params). */
        public TypeRef memberSource() {
            if (arrayRank > 0) return external("Array", List.of());
            if (kind == Kind.TYPE_PARAM) return constraint != null ? constraint.memberSource() : UNKNOWN;
            return this;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(kind == Kind.PROJECT ? decl.fullName() : name);
            if (!args.isEmpty()) sb.append('<').append(args).append('>');
            for (int i = 0; i < arrayRank; i++) sb.append("[]");
            return sb.toString();
        }
    }

    /** Whole-project index. */
    public static final class Project {
        public final List<CsFile> files = new ArrayList<>();
        public final Map<String, TypeDecl> typesByKey = new LinkedHashMap<>();   // key() -> decl
        public final Map<String, List<TypeDecl>> typesByName = new HashMap<>();  // simple name -> decls
        public final Set<String> namespaces = new HashSet<>();                    // all namespace names and prefixes
        public final Set<String> allIdentifiers = new HashSet<>();               // every identifier in the input
        public final Set<String> stringWords = new HashSet<>();                  // words appearing inside string literals
        public final Set<String> namedArguments = new HashSet<>();               // names used as named arguments

        public void addType(TypeDecl t) {
            typesByKey.put(t.key(), t);
            typesByName.computeIfAbsent(t.name, k -> new ArrayList<>()).add(t);
        }

        public TypeDecl findType(String fullName, int arity) {
            return typesByKey.get(fullName + "`" + arity);
        }

        /** Find a type by full name with any arity, preferring the requested arity. */
        public TypeDecl findTypeAnyArity(String fullName, int arity) {
            TypeDecl t = findType(fullName, arity);
            if (t != null) return t;
            for (TypeDecl cand : typesByKey.values()) {
                if (cand.fullName().equals(fullName)) return cand;
            }
            return null;
        }

        public void addNamespace(String ns) {
            String[] parts = ns.split("\\.");
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (sb.length() > 0) sb.append('.');
                sb.append(p);
                namespaces.add(sb.toString());
            }
        }
    }
}
