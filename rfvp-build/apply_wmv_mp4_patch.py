#!/usr/bin/env python3
"""
rfvp「WMV 优先吃预转好的 MP4」补丁（YukiHub 定制）。

背景：rfvp 原生 WMV 路径的视频解码没问题（3.5× 实时），但 WMA 音频解码
比实时慢 5.3×（na_wmv_player 的 MdctNaive 没上 FFT，见 ENGINE_PATCHES.md §3.3.1），
所以 WMV 一直"能播画面但没声音"，玩家只能靠外部工具预先把视频转成 MP4。

YukiHub 现在自带 v2video 内核（MediaCodec 硬编 H.264 + 我们的 AAC），
游戏启动后会在后台把 *.wmv/*.asf 转成 .rfvp_cache/video/<同名>.mp4 ——
正好落在 rfvp 自己的视频缓存目录里。本补丁让 open() 在处理 .wmv/.asf 时
先看这个缓存里有没有同名 .mp4，有就直接走已经跑通的 MP4 路径
（MediaCodec 硬解 + symphonia 解 AAC + Kira 出声），没有就退回原生 WMV 路径。

注意：
- **不改 WMV 播放逻辑本身**（遵循 ENGINE_PATCHES.md 的约定），只是"有 MP4 就用 MP4"。
- 只对**散文件**生效：电影打在 pack 里的游戏，宿主侧读不到包内内容，
  仍走原生路径。后续要做解包预转再说。
- 需要 rfvp 以 `mp4` feature 编译（YukiHub 的构建就是）。

用法（在 rfvp 仓库根目录）：
    python3 apply_wmv_mp4_patch.py

锚文本严格校验 + 幂等，风格与 apply_movie_patch.py 一致。
锚点针对 rfvp **main** 分支（videoplayer 已拆成目录，threaded.rs 里按扩展名分派）。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent

# (相对路径, [(旧文本, 新文本, 期望命中次数), ...])
EDITS = [
    (
        "crates/rfvp/src/subsystem/resources/videoplayer/threaded.rs",
        [
            (
                """    if ext == "wmv" || ext == "asf" {
        Ok(Box::new(WmvPlayback::open_from_wmv_path(
            &path, mode, screen_w, screen_h, audio,
        )?))""",
                """    if ext == "wmv" || ext == "asf" {
        // YukiHub patch: 优先吃预转好的同名 MP4（.rfvp_cache/video/<相对路径>.mp4）。
        // 由宿主在游戏启动后用 v2video 内核（MediaCodec 硬编）在后台转好；
        // 没有就退回原生 WMV 路径 —— WMV 的播放逻辑本身一字不动。
        #[cfg(feature = "mp4")]
        if let Ok(rel) = safe_rel_path_from_vfs(request.name) {
            let cached = video_cache_root_dir().join(rel).with_extension("mp4");
            if cached.exists() {
                log::info!("Movie: using transcoded mp4 {}", cached.display());
                return Ok(Box::new(Mp4Playback::open_from_mp4_bytes(
                    &cached, mode, audio.clone(),
                )?));
            }
        }
        Ok(Box::new(WmvPlayback::open_from_wmv_path(
            &path, mode, screen_w, screen_h, audio,
        )?))""",
                1,
            ),
        ],
    ),
]


def main() -> int:
    total = 0
    for rel, edits in EDITS:
        path = ROOT / rel
        if not path.exists():
            print(f"[fail-fast] 找不到 {rel} —— 这个补丁只适配 videoplayer 已拆成目录的 rfvp main", file=sys.stderr)
            return 1
        text = path.read_text(encoding="utf-8")
        if "YukiHub patch: 优先吃预转好的同名 MP4" in text:
            print(f"[skip] {rel}: 已打过，幂等退出")
            continue
        for old, new, expect in edits:
            hits = text.count(old)
            if hits != expect:
                print(
                    f"[fail-fast] {rel}: 锚文本命中 {hits} 次（期望 {expect}）。\n"
                    f"  上游代码和锚点不一致，别硬套 —— 先 diff 看看 open() 现在长什么样。",
                    file=sys.stderr,
                )
                return 1
            text = text.replace(old, new)
            total += expect
        path.write_text(text, encoding="utf-8")
        print(f"[ok] {rel}: {len(edits)} 处补丁写入")
    print(f"[done] 共 {total} 处。重编 librfvp.so 后，WMV 就能吃到宿主转好的 MP4 了。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
