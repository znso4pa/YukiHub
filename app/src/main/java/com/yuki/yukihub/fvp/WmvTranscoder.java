package com.yuki.yukihub.fvp;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * WMV/ASF → MP4 后台转码器（v2video 内核，MediaCodec 硬编）。
 *
 * <p>背景：FVP 引擎（rfvp）自己那套 WMA 解码比实时慢 5.3×（见
 * rfvp-build/ENGINE_PATCHES.md §3.3.1），所以原版 .wmv 能播画面但没有声音。
 * 本类在游戏启动后于后台把片头/过场转成 MP4，放进 rfvp 的视频缓存目录
 * （{@code <游戏目录>/.rfvp_cache/video/...}）—— 引擎下次要放同名视频时
 * 就会吃到 MP4（那个 5 行分派补丁）。缓存一次，之后随时能放。
 *
 * <p>设计要点：
 * <ul>
 *   <li>**不阻塞开局**：转码在后台线程，玩家点进还没转完的 OP 时引擎会退回
 *       原生 WMV 路径（照旧能播、只是没声音），下次进来就是 MP4。</li>
 *   <li>**串行单线程**：不与引擎抢 CPU，转码是能后台慢慢来的事。</li>
 *   <li>**跳过已完成**：目标文件存在且非空就不重转；换引擎重进也直接复用。</li>
 *   <li>**库缺失安全**：.so 没打进包里时整类静默变成 no-op，游戏照常能玩。</li>
 * </ul>
 *
 * <p>线程模型：{@link #start} 从 Activity 主线程调，转码在 daemon 线程跑；
 * {@link #stop} 用 {@code interrupt} 协作式取消（转码内核每帧都会看中断位）。
 */
public final class WmvTranscoder {

    private static final String TAG = "WmvTranscoder";

    /** 视频 / 音频目标码率（bit/s）。720p 用 4M；1080p 的游戏音画同码率问题不大。 */
    private static final int VIDEO_BITRATE = 4_000_000;
    private static final int AUDIO_BITRATE = 128_000;

    /** 转码目标的缓存根目录名 —— 必须与 rfvp 的 video_cache_root_dir() 一致。 */
    private static final String CACHE_DIR = ".rfvp_cache/video";

    static {
        boolean ok = false;
        try {
            System.loadLibrary("v2vjni");
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "libv2vjni.so 缺失，转码功能不可用: " + t);
        }
        sLibLoaded = ok;
    }

    private static final boolean sLibLoaded;

    private static volatile Thread sWorker;

    private WmvTranscoder() { }

    /** 硬件编码后端是否可用（库没打进包或设备太老都算不可用）。 */
    public static boolean isAvailable() {
        return sLibLoaded && nativeHwSupported() == 1;
    }

    /**
     * 开始后台转码 {@code gameRoot} 下的所有 WMV/ASF。
     * 重复调用会先停掉上一个（换游戏目录时用）。
     */
    public static void start(String gameRoot) {
        if (!sLibLoaded || gameRoot == null || gameRoot.isEmpty()) return;
        stop();

        final String root = gameRoot;
        Thread worker = new Thread(() -> {
            try {
                convertAll(new File(root));
            } catch (Throwable t) {
                Log.w(TAG, "后台转码中断: " + t);
            }
        }, "v2v-transcode");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY + 1);
        sWorker = worker;
        worker.start();
    }

    /** 停止后台转码（协作式：当前这个文件转完就退出，不硬杀）。 */
    public static void stop() {
        Thread worker = sWorker;
        sWorker = null;
        if (worker != null) worker.interrupt();
    }

    private static void convertAll(File gameRoot) {
        if (!isAvailable()) {
            Log.i(TAG, "硬件后端不可用，跳过转码");
            return;
        }
        List<File> movies = new ArrayList<>();
        collect(gameRoot, movies);
        if (movies.isEmpty()) return;

        File cacheRoot = new File(gameRoot, CACHE_DIR);
        Log.i(TAG, "发现 " + movies.size() + " 个 WMV/ASF，开始后台转码");

        for (File src : movies) {
            if (sWorker == null || Thread.currentThread().isInterrupted()) {
                Log.i(TAG, "转码被取消");
                return;
            }

            File dst = transcodeTarget(gameRoot, cacheRoot, src);
            if (dst == null) continue;
            if (dst.isFile() && dst.length() > 0) continue;   // 已转过

            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                Log.w(TAG, "建缓存目录失败: " + parent);
                continue;
            }

            long begin = System.currentTimeMillis();
            int rc = nativeTranscode(src.getAbsolutePath(), dst.getAbsolutePath(),
                    VIDEO_BITRATE, AUDIO_BITRATE);
            long cost = (System.currentTimeMillis() - begin) / 1000;
            if (rc == 0) {
                Log.i(TAG, "转码完成 " + src.getName() + " -> " + dst.getName()
                        + " (" + cost + "s)");
            } else {
                Log.w(TAG, "转码失败 rc=" + rc + " " + src.getName()
                        + ": " + nativeLastError());
            }
        }
    }

    /** 转码产物的落点：{@code <gameRoot>/.rfvp_cache/video/<相对路径>.mp4}。 */
    private static File transcodeTarget(File gameRoot, File cacheRoot, File src) {
        String rel = gameRoot.toURI().relativize(src.toURI()).getPath();
        if (rel.isEmpty()) return null;
        String base = rel.substring(0, rel.lastIndexOf('.') < 0 ? rel.length() : rel.lastIndexOf('.'));
        return new File(cacheRoot, base + ".mp4");
    }

    /** 递归收集 WMV/ASF；跳过引擎自己的缓存与存档目录。 */
    private static void collect(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".rfvp_cache") || name.equals("save") || name.equals("savedata")) {
                continue;
            }
            if (f.isDirectory()) {
                collect(f, out);
            } else {
                String lower = name.toLowerCase();
                if (lower.endsWith(".wmv") || lower.endsWith(".asf")) out.add(f);
            }
        }
    }

    // ── JNI（实现在 app/src/main/cpp/v2v_bridge.c）────────────────────────────

    private static native int nativeTranscode(String input, String output,
                                              int videoBitrate, int audioBitrate);

    private static native String nativeLastError();

    private static native int nativeHwSupported();
}
