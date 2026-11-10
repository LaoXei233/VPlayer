package com.vplayer.client;

import com.vplayer.VPlayerMod;
import net.minecraft.client.Minecraft;
import org.bytedeco.javacpp.Loader;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 加载 JavaCV / FFmpeg 原生库。
 * 由模组自己把 jar 内的 dll 解压到游戏目录下的 vplayer-natives，
 * 再让 JavaCPP 直接从该目录加载（避免 JavaCPP 复制文件时被安全软件锁定）。
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
            int extracted = 0;
            try {
                extracted = extractNatives(dir);
                if (extracted == 0) {
                    throw new IllegalStateException("未找到可解压的原生库（jar 路径解析失败）");
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
        Path jarPath = findModJar();
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            throw new IllegalStateException("找不到模组 jar: " + jarPath);
        }
        String platform = Loader.getPlatform();
        int count = 0;
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
        return count;
    }

    /** 通过 Forge 的 ModList 获取模组 jar 的真实磁盘路径。 */
    private static Path findModJar() {
        try {
            var info = net.neoforged.fml.ModList.get().getModFileById(VPlayerMod.MODID);
            if (info != null) {
                return info.getFile().getFilePath();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File nativeDir() {
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
