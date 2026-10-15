package sts1solver;

import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.ConstPool;
import javassist.bytecode.annotation.*;

/** Finds loaded mods that patch the game's Random or draw from it, which desyncs the solver's RNG. */
final class RngMods {
    private static final String RANDOM = "com.megacrit.cardcrawl.random.Random";
    // Libraries only draw on behalf of the game or of other mods; this mod draws in its own sandbox;
    // State Notes (paired in CI) bundles savestate copies that repeat vanilla's draws one for one.
    private static final Set<String> TRUSTED = new HashSet<>(Arrays.asList("basemod", "stslib", "sts1solver", "sts1-state-notes"));
    // copy() and the counter field are read-only; every other Random method advances or resets a stream.
    private static final Set<String> READ_ONLY = new HashSet<>(Arrays.asList("<init>", "copy"));

    private RngMods() { }

    // shortcut: only direct Random calls and patches on Random are seen, not game helpers
    // (e.g. returnRandomCard) a mod calls; upgrade to patch-target analysis if users report misses.
    static List<String> loaded() {
        List<String> names = new ArrayList<>();
        if (Loader.MODINFOS == null) return names;
        for (ModInfo mod : Loader.MODINFOS) {
            if (mod.ID == null || TRUSTED.contains(mod.ID.toLowerCase(Locale.ROOT)) || mod.jarURL == null) continue;
            try {
                if (jarTouchesRng(new File(mod.jarURL.toURI()))) names.add(mod.Name == null ? mod.ID : mod.Name);
            } catch (Exception failure) {
                System.err.println("[STS1Solver] cannot scan " + mod.ID + ": " + failure);
            }
        }
        return names;
    }

    static boolean jarTouchesRng(File jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    if (classTouchesRng(in)) return true;
                }
            }
        }
        return false;
    }

    static boolean classTouchesRng(InputStream in) throws IOException {
        ClassFile file = new ClassFile(new DataInputStream(in));
        ConstPool pool = file.getConstPool();
        for (int i = 1; i < pool.getSize(); i++)
            if (pool.getTag(i) == ConstPool.CONST_Methodref && RANDOM.equals(pool.getMethodrefClassName(i))
                    && !READ_ONLY.contains(pool.getMethodrefName(i))) return true;
        AnnotationsAttribute annotations = (AnnotationsAttribute) file.getAttribute(AnnotationsAttribute.visibleTag);
        if (annotations != null) for (Annotation annotation : annotations.getAnnotations())
            if (patchesRandom(annotation)) return true;
        return false;
    }

    // @SpirePatch(clz=Random.class) / cls="...Random", also inside @SpirePatches containers.
    private static boolean patchesRandom(Annotation annotation) {
        MemberValue clz = annotation.getMemberValue("clz"), cls = annotation.getMemberValue("cls");
        if (clz instanceof ClassMemberValue && RANDOM.equals(((ClassMemberValue) clz).getValue())) return true;
        if (cls instanceof StringMemberValue && RANDOM.equals(((StringMemberValue) cls).getValue())) return true;
        MemberValue value = annotation.getMemberValue("value");
        if (value instanceof ArrayMemberValue) for (MemberValue item : ((ArrayMemberValue) value).getValue())
            if (item instanceof AnnotationMemberValue && patchesRandom(((AnnotationMemberValue) item).getValue())) return true;
        return false;
    }
}
