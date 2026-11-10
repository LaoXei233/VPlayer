package com.vplayer.client;

import com.vplayer.VPlayerMod;
import net.minecraft.client.Minecraft;
import org.bytedeco.javacpp.Loader;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 加载 JavaCV / FFmpeg 原生库。
 * 由模组自己把 dll 解压到游戏目录下的 vplayer-natives，再让 JavaCPP 直接从该目录加载
 * （避免 JavaCPP 复制文件时被安全软件锁定）。
 */
public final class VPlayerNatives {

    private static final Object LOCK = new Object();
    private static volatile boolean loaded = false;
    private static volatile String error = null;

    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            File dir = nativeDir();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                error = "无法创建原生库目录: " + dir;
                VPlayerMod.LOGGER.error("[VPlayer] {}", error);
                return;
            }
            // 必须在任何 Loader 静态初始化（包括 getPlatform()）之前设置这些属性：
            // Loader 只在类初始化时读取一次系统属性。
            String nativePath = dir.getAbsolutePath();
            // pathsfirst：优先用我们已经解压好的目录，而不是从类路径（游戏内是 securejarhandler
            // 的 union: URL）里解压。否则 JavaCPP 会反复尝试覆盖已加载的 dll，Windows 下报
            // “另一个程序正在使用此文件”。
            System.setProperty("org.bytedeco.javacpp.pathsfirst", "true");
            System.setProperty("org.bytedeco.javacpp.cachedir", nativePath);
            System.setProperty("org.bytedeco.javacpp.platform.preloadpath", nativePath);
            int extracted;
            try {
                extracted = extractNatives(dir);
                if (extracted == 0) {
                    throw new IllegalStateException("未找到可解压的原生库");
                }
            } catch (Throwable t) {
                error = "解压原生库失败: " + t;
                VPlayerMod.LOGGER.error("[VPlayer] 解压原生库失败", t);
                return;
            }
            VPlayerMod.LOGGER.info("[VPlayer] 原生库目录: {} (已就绪 {} 个文件)", dir, extracted);
            try {
                loadFrom(dir);
                loaded = true;
                VPlayerMod.LOGGER.info("[VPlayer] FFmpeg 原生库加载成功");
                return;
            } catch (Throwable t) {
                error = String.valueOf(t);
                VPlayerMod.LOGGER.warn("[VPlayer] 从本地目录加载失败，清理后重试: {}", error);
            }
            // 清理后重试一次
            try {
                deleteRecursively(dir);
                dir.mkdirs();
                extractNatives(dir);
                loadFrom(dir);
                loaded = true;
                VPlayerMod.LOGGER.info("[VPlayer] FFmpeg 原生库加载成功（重试）");
            } catch (Throwable t) {
                error = String.valueOf(t);
                VPlayerMod.LOGGER.error("[VPlayer] FFmpeg 原生库加载失败", t);
            }
        }
    }

    private static void loadFrom(File dir) {
        String path = dir.getAbsolutePath();
        System.setProperty("org.bytedeco.javacpp.cachedir", path);
        System.setProperty("org.bytedeco.javacpp.platform.preloadpath", path);
        Loader.loadProperties(true);
        Loader.load(org.bytedeco.ffmpeg.global.avutil.class, Loader.loadProperties(), true);
    }

    /** 把 jar 内当前平台的 dll 解压到目录（扁平结构）。 */
    private static int extractNatives(File dir) throws Exception {
        String platform = Loader.getPlatform();
        List<Path> jars = candidateJars();
        if (jars.isEmpty()) {
            throw new IllegalStateException("找不到包含原生库的 jar");
        }
        Set<String> seen = new LinkedHashSet<>();
        int count = 0;
        for (Path jarPath : jars) {
            if (jarPath == null || !Files.isRegularFile(jarPath)) {
                continue;
            }
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.endsWith(".dll") || !name.startsWith("org/bytedeco/")) {
                        continue;
                    }
                    if (!name.contains("/" + platform + "/")) {
                        continue;
                    }
                    String fileName = name.substring(name.lastIndexOf('/') + 1);
                    if (!seen.add(fileName)) {
                        continue;
                    }
                    File out = new File(dir, fileName);
                    if (out.isFile() && out.length() == entry.getSize()) {
                        count++;
                        continue;
                    }
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    count++;
                }
            }
        }
        return count;
    }

    private static void addMatchingJars(List<Path> result, String classpath, String separator) {
        if (classpath == null || classpath.isBlank()) {
            return;
        }
        for (String part : classpath.split(java.util.regex.Pattern.quote(separator))) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String lower = p.toLowerCase();
            if (lower.endsWith(".jar") && (lower.contains("javacv") || lower.contains("ffmpeg") || lower.contains("javacpp"))) {
                result.add(Paths.get(p));
            }
        }
    }

    /**
     * 可能存放原生库的 jar 列表：
     * 1) 正式环境：模组自身的 jar（fatJar 内含 dll）
     * 2) 开发环境：JavaCPP 依赖 jar（classpath 扫描）
     */
    private static List<Path> candidateJars() {
        List<Path> result = new ArrayList<>();
        Path modJar = findModJar();
        if (modJar != null) {
            result.add(modJar);
        }
        addMatchingJars(result, System.getProperty("java.class.path", ""), File.pathSeparator);
        return result;
    }

    /** 找到模组 jar 的真实磁盘路径（正式环境）。 */
    private static Path findModJar() {
        try {
            var info = net.minecraftforge.fml.ModList.get().getModFileById(VPlayerMod.MODID);
            if (info != null) {
                return info.getFile().getFilePath();
            }
        } catch (Throwable ignored) {
        }
        // 回退：从自身字节码位置推断（普通 JVM / 直接运行 fatJar 时）
        try {
            URI uri = VPlayerNatives.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(uri);
            if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                return path;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File nativeDir() {
        String override = System.getProperty("vplayer.natives.dir");
        if (override != null && !override.isBlank()) {
            return new File(override);
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                return new File(mc.gameDirectory, "vplayer-natives");
            }
        } catch (Throwable ignored) {
        }
        return new File(System.getProperty("java.io.tmpdir"), "vplayer-natives");
    }

    private static void deleteRecursively(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                deleteRecursively(f);
            }
            f.delete();
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static String getError() {
        return error;
    }

    private VPlayerNatives() {}
}
