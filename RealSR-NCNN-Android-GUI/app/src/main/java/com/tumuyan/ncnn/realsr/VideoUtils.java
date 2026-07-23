package com.tumuyan.ncnn.realsr;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Video upscaling support, built on the same pattern the app already uses for
 * ImageMagick / realsr-ncnn: a static CLI binary is dropped into assets/realsr,
 * copied to the app's private working dir by AssetsCopyer at startup, chmod +x'd,
 * and driven with plain shell commands via ImageProcessor.
 *
 * Pipeline (mirrors the desktop/Colab approach):
 *   1) ffmpeg:  video  -> frames (input.png/frame_%08d.png)
 *   2) realsr-ncnn (existing binary, directory mode): frames -> upscaled frames
 *   3) ffmpeg:  upscaled frames (+ original audio) -> output video
 *   4) optional: ffmpeg scale+unsharp pass to cap the result at 1080p
 *
 * This class only builds shell command strings and parses ffprobe output; it does
 * not run any UI or Activity code, so it can be unit-tested on its own.
 *
 * REQUIRES: static `ffmpeg` and `ffprobe` binaries (arm64-v8a) placed alongside the
 * existing binaries in app/src/main/assets/realsr/. ffmpeg-kit-next ships source-only
 * (no prebuilt AAR), so building it needs the NDK and a from-source build that can take
 * hours; a standalone static ffmpeg/ffprobe binary dropped in as an asset is far less
 * work and slots into the app's existing "shell out to a bundled binary" pattern with
 * zero JNI/native build changes required.
 */
public class VideoUtils {

    private static final String TAG = "VideoUtils";

    /** Extensions treated as video input. Extend as needed. */
    private static final String[] VIDEO_EXTENSIONS = {
            ".mp4", ".mkv", ".mov", ".avi", ".webm", ".m4v", ".3gp", ".ts", ".flv"
    };

    public static boolean isVideoFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    public static boolean isVideoMimeType(String mimeType) {
        return mimeType != null && mimeType.startsWith("video/");
    }

    /** Metadata pulled from ffprobe, needed to rebuild the video correctly. */
    public static class VideoInfo {
        public double fps = 30.0;
        public int width;
        public int height;
        public boolean hasAudio;
        public double durationSeconds;

        @Override
        public String toString() {
            return "VideoInfo{fps=" + fps + ", " + width + "x" + height
                    + ", hasAudio=" + hasAudio + ", duration=" + durationSeconds + "}";
        }
    }

    /**
     * Runs `ffprobe` directly (no shell) against the given video and parses the
     * JSON result. workingDir must be the app's binary dir (dir field in
     * MainActivity) since that's where ffprobe/ffmpeg were extracted to.
     */
    public static VideoInfo probe(String workingDir, String videoPath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                workingDir + "/ffprobe",
                "-v", "error",
                "-show_entries", "stream=width,height,r_frame_rate,avg_frame_rate,codec_type",
                "-show_entries", "format=duration",
                "-of", "json",
                videoPath
        );
        pb.directory(new File(workingDir));
        pb.environment().put("LD_LIBRARY_PATH", workingDir);
        pb.redirectErrorStream(false);

        Process process = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            throw new IOException("ffprobe timed out on " + videoPath);
        }

        VideoInfo info = new VideoInfo();
        try {
            JSONObject root = new JSONObject(sb.toString());
            JSONArray streams = root.optJSONArray("streams");
            if (streams != null) {
                for (int i = 0; i < streams.length(); i++) {
                    JSONObject stream = streams.getJSONObject(i);
                    String type = stream.optString("codec_type");
                    if ("video".equals(type) && info.width == 0) {
                        info.width = stream.optInt("width");
                        info.height = stream.optInt("height");
                        info.fps = parseFrameRate(stream.optString("avg_frame_rate",
                                stream.optString("r_frame_rate", "30/1")));
                    } else if ("audio".equals(type)) {
                        info.hasAudio = true;
                    }
                }
            }
            JSONObject format = root.optJSONObject("format");
            if (format != null) {
                info.durationSeconds = format.optDouble("duration", 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse ffprobe output: " + sb, e);
        }
        return info;
    }

    private static double parseFrameRate(String rate) {
        try {
            if (rate.contains("/")) {
                String[] parts = rate.split("/");
                double num = Double.parseDouble(parts[0]);
                double den = Double.parseDouble(parts[1]);
                return den == 0 ? 30.0 : num / den;
            }
            return Double.parseDouble(rate);
        } catch (Exception e) {
            return 30.0;
        }
    }

    // ---------------------------------------------------------------------
    // Shell command builders. All paths passed in should already be absolute
    // (or relative to workingDir, which is where the shell `cd`s to) and are
    // escaped with ShellUtils.escapeShellArgument by the caller if they come
    // from user/URI input. Frame directories are always app-internal paths,
    // so they're safe to inline directly.
    // ---------------------------------------------------------------------

    /**
     * Step 1: extract every frame of the source video into framesDir as PNGs.
     * PNG is lossless (matches how single-image upscales are handled) but is
     * slower to write than JPEG; see {@link #buildExtractFramesCommandFast}
     * for a quicker, slightly lossy alternative on long clips.
     */
    public static String buildExtractFramesCommand(String videoPathEscaped, String framesDirName) {
        return "./ffmpeg -y -i " + videoPathEscaped
                + " -vsync 0 -qscale:v 1 "
                + framesDirName + "/frame_%08d.png";
    }

    /** Faster variant using high-quality JPEG frames instead of PNG. */
    public static String buildExtractFramesCommandFast(String videoPathEscaped, String framesDirName) {
        return "./ffmpeg -y -i " + videoPathEscaped
                + " -vsync 0 -q:v 2 "
                + framesDirName + "/frame_%08d.jpg";
    }

    /**
     * Step 3: reassemble the upscaled frames back into a video, muxing the
     * original file's audio track back in (if it had one) without re-encoding it.
     *
     * @param fps            frame rate probed from the source video
     * @param framesDirName  directory of upscaled frames (e.g. "output.png")
     * @param frameExt       "png" or "jpg", matching whichever extractor was used
     * @param originalVideoPathEscaped source video, used only to pull the audio stream
     * @param hasAudio       whether the source has an audio stream to mux back in
     * @param outputPathEscaped final destination path (already shell-escaped)
     */
    public static String buildReassembleCommand(double fps, String framesDirName, String frameExt,
                                                 String originalVideoPathEscaped, boolean hasAudio,
                                                 String outputPathEscaped) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("./ffmpeg -y -r ").append(trimFps(fps))
                .append(" -i ").append(framesDirName).append("/frame_%08d.").append(frameExt);

        if (hasAudio) {
            cmd.append(" -i ").append(originalVideoPathEscaped)
                    .append(" -map 0:v:0 -map 1:a:0 -c:a copy -shortest");
        }

        cmd.append(" -c:v libx264 -pix_fmt yuv420p -crf 18 -preset veryfast ")
                .append(outputPathEscaped);
        return cmd.toString();
    }

    /**
     * Step 4 (optional): cap the reassembled video to maxHeight, translated
     * directly from the desktop/Colab filter chain (lanczos scale + a light
     * unsharp pass to recover detail the downscale softens). Only invoke this
     * when the reassembled video is actually taller than maxHeight.
     */
    public static String buildCapResolutionCommand(String inputPathEscaped, String outputPathEscaped, int maxHeight) {
        String vf = "scale=-2:" + maxHeight + ":flags=lanczos+accurate_rnd,unsharp=5:5:0.8:3:3:0.4";
        return "./ffmpeg -y -i " + inputPathEscaped
                + " -vf " + ShellUtils.escapeShellArgument(vf)
                + " -c:v libx264 -crf 17 -preset medium -c:a copy "
                + outputPathEscaped;
    }

    /** Removes the extracted/upscaled frame directories and any temp video files. */
    public static String buildCleanupCommand(String... paths) {
        StringBuilder cmd = new StringBuilder("rm -rf");
        for (String p : paths) {
            cmd.append(' ').append(p);
        }
        return cmd.toString();
    }

    private static String trimFps(double fps) {
        // ffmpeg accepts fractional -r values directly; avoid "30.0" -> keep it terse
        if (fps == Math.floor(fps)) {
            return String.valueOf((int) fps);
        }
        return String.format(Locale.ROOT, "%.3f", fps);
    }
}
