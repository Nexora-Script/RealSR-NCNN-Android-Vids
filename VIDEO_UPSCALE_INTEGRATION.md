# Adding video upscaling to RealSR-NCNN-Android-GUI

## Why not ffmpeg-kit-next

I checked the tarball you uploaded — `ffmpeg-kit-next` ships **source only**, no
prebuilt AAR. Building it yourself means installing the Android NDK, running
`android.sh`, and waiting a couple of hours per architecture, all without a
guarantee it links cleanly against `compileSdk 36` / API 28 target in this project.

Your app already solves this exact problem a different way: `realsr-ncnn` and
`magick` are static CLI binaries dropped into `assets/realsr/`, copied to the
app's private dir by `AssetsCopyer` at startup, `chmod +x`'d, and driven with
plain shell commands through `ImageProcessor`. Doing the same with a **static
`ffmpeg`/`ffprobe` binary** (arm64‑v8a) is far less work, needs zero JNI/Gradle
changes, and is what `VideoUtils.java` (attached) assumes.

Grab a static Android build of ffmpeg (several projects publish these, e.g.
`Javernaut/ffmpeg-android-maker` builds, or any arm64‑v8a static `ffmpeg`/`ffprobe`
pair) and drop both binaries into `app/src/main/assets/realsr/` next to
`realsr-ncnn`. That's the only "build step" required.

## The pipeline

This mirrors the GIF handling that's already in `MainActivity` (search
`inputIsGifAnimation` — it already extracts frames to a directory, runs the
upscaler in **directory batch mode**, then reassembles). Video reuses the exact
same shape, swapping ImageMagick's GIF coalesce/assemble for ffmpeg:

1. `ffmpeg` extracts every frame of the source video → `input.png/frame_%08d.png`
2. `realsr-ncnn -i input.png -o output.png ...` (already directory-aware, one
   process for the whole clip — no per-frame spawn overhead, this is what
   keeps it fast)
3. `ffmpeg` reassembles `output.png/frame_*.png` at the original fps, muxes the
   original audio back in with `-c:a copy` (no re-encode)
4. Optional: if the result is taller than 1080p, one more `ffmpeg` pass with
   your `scale=lanczos+unsharp` filter chain caps it

## Files to add

- `VideoUtils.java` → `app/src/main/java/com/tumuyan/ncnn/realsr/VideoUtils.java`
  (attached — self-contained, no other files need to exist for it to compile)

## Touch points in `MainActivity.java`

### 1. Fields (near line 1138, next to `inputIsGifAnimation`)

```java
private boolean inputIsGifAnimation;
private int inputGifDelay;
// --- add ---
private boolean inputIsVideo;
private VideoUtils.VideoInfo inputVideoInfo;
private String inputVideoPath; // absolute path to the copied source video
```

### 2. File picker MIME types (around lines 580 and 588)

```java
// before
Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
intent.setType("image/*");

// after
Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
intent.setType("*/*");
intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
```

Apply the same change to the second `i.setType("image/*")` a few lines down.

### 3. Detecting a video pick, in `saveInputImage(...)` (line ~1148)

Video needs a different branch than the PNG-preprocessing path (magic-byte
sniffing only recognizes image formats). Easiest is to check the picked file's
name/MIME **before** calling `saveInputImage`, at the point the picker result
is handled, and route to a new method instead:

```java
if (VideoUtils.isVideoFile(fileName)) {
    handleVideoInput(inputStream, fileName);
} else {
    saveInputImage(inputStream, "");
}
```

```java
private boolean handleVideoInput(@NonNull InputStream in, String fileName) {
    inputIsGifAnimation = false;
    inputIsVideo = false;
    inputVideoPath = dir + "/input_video" + fileName.substring(fileName.lastIndexOf('.'));

    try (OutputStream out = new FileOutputStream(inputVideoPath)) {
        byte[] buffer = new byte[65536];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    } catch (IOException e) {
        e.printStackTrace();
        return false;
    }

    try {
        inputVideoInfo = VideoUtils.probe(dir, inputVideoPath);
    } catch (Exception e) {
        e.printStackTrace();
        Toast.makeText(this, "ffprobe failed — check ffmpeg/ffprobe are in assets/realsr", Toast.LENGTH_LONG).show();
        return false;
    }

    deleteFile(inputFile);
    inputFile.mkdirs();
    boolean fast = mySharePerferences.getBoolean("fastVideoFrames", false);
    String extractCmd = fast
            ? VideoUtils.buildExtractFramesCommandFast(ShellUtils.escapeShellArgument(inputVideoPath), "input.png")
            : VideoUtils.buildExtractFramesCommand(ShellUtils.escapeShellArgument(inputVideoPath), "input.png");
    run_command(extractCmd);

    inputIsVideo = true;
    return true;
}
```

`run_command` here is the same synchronous helper the GIF path already uses
(`run_command("./magick tmp -coalesce ...")` a few lines below) — it already
`cd`s into `dir` and sets `LD_LIBRARY_PATH`, so `./ffmpeg` resolves correctly.

### 4. `run20()` — extend the directory-export guard (line ~926)

```java
// before
if (inputFile.isDirectory() && !inputIsGifAnimation) {

// after
if (inputFile.isDirectory() && !inputIsGifAnimation && !inputIsVideo) {
```

### 5. `run20()` — the `export_one_file` condition (line ~991)

```java
// before
boolean export_one_file = run_ncnn && (autoSave || (inputFile.isDirectory() && inputIsGifAnimation))
        && cmd.contains("output.png");

// after
boolean export_one_file = run_ncnn && (autoSave || (inputFile.isDirectory() && (inputIsGifAnimation || inputIsVideo)))
        && cmd.contains("output.png");
```

### 6. `run20()` — reassembly branch (line ~1007, right next to the GIF branch)

```java
if (save) {
    String export_cmd = saveOutputCmd();
    if (inputIsGifAnimation) {
        builder.append(";./magick -delay " + inputGifDelay + " output.png/* -loop 0 " + ShellUtils.escapeShellArgument(outputSavePath));
    } else if (inputIsVideo) {
        boolean fast = mySharePerferences.getBoolean("fastVideoFrames", false);
        String frameExt = fast ? "jpg" : "png";
        String reassemble = VideoUtils.buildReassembleCommand(
                inputVideoInfo.fps, "output.png", frameExt,
                ShellUtils.escapeShellArgument(inputVideoPath),
                inputVideoInfo.hasAudio,
                ShellUtils.escapeShellArgument(outputSavePath));
        builder.append(";" + reassemble);

        boolean cap1080p = mySharePerferences.getBoolean("cap1080pVideo", true);
        if (cap1080p && inputVideoInfo.height > 1080) {
            String capped = outputSavePath.replaceFirst("\\.mp4$", "_1080p.mp4");
            builder.append(";" + VideoUtils.buildCapResolutionCommand(
                    ShellUtils.escapeShellArgument(outputSavePath),
                    ShellUtils.escapeShellArgument(capped), 1080));
            builder.append(";mv " + ShellUtils.escapeShellArgument(capped) + " " + ShellUtils.escapeShellArgument(outputSavePath));
        }
        builder.append(";" + VideoUtils.buildCleanupCommand("input.png", "output.png", ShellUtils.escapeShellArgument(inputVideoPath)));
    } else {
        builder.append(";" + export_cmd);
    }
}
```

`outputSavePath` needs a `.mp4` extension when `inputIsVideo` is true — wherever
that path gets built for images, branch it to something like
`galleryPath + "/" + baseName + "_upscaled.mp4"` for video.

## Settings: 1080p cap toggle

Follow the exact pattern already used for `autoSave` / `useCPU` in
`SettingActivity.java`:

```java
// load (near line 68-78, alongside the other getBoolean calls)
boolean cap1080pVideo = mySharePerferences.getBoolean("cap1080pVideo", true);
boolean fastVideoFrames = mySharePerferences.getBoolean("fastVideoFrames", false);

// save (near line 307-315, alongside the other putBoolean calls)
editor.putBoolean("cap1080pVideo", toggleCap1080p.isChecked());
editor.putBoolean("fastVideoFrames", toggleFastFrames.isChecked());
```

Add two `Switch`/`CheckBoxPreference` views to the settings layout XML the same
way the existing toggles are declared, wire `toggleCap1080p` /
`toggleFastFrames` to them via `findViewById`, and you're done — same wiring as
every other setting in that file.

## Notes on speed

- The upscale step is the bottleneck either way (same NCNN model, same cost
  per frame as a photo) — nothing about video handling changes that.
- Directory-mode `realsr-ncnn` loads the model once and streams frames through
  it with its own load/proc/save thread pipeline (`-j load:proc:save`), so
  it's not paying process-startup cost per frame.
- The `fastVideoFrames` toggle trades PNG frames for JPEG (`-q:v 2`) on both
  the extract and reassemble side — meaningfully faster I/O on longer clips at
  a barely-visible quality cost, since the frames get re-encoded to H.264
  afterward anyway.
- `preset veryfast` is used for the main encode and `preset medium` for the
  1080p cap pass (your reference script used `slow`, which is fine on a
  desktop CPU but will be very slow on a phone SoC).
- Frame directories are deleted right after reassembly (`buildCleanupCommand`)
  since a few thousand PNG frames can easily eat a few GB of phone storage
  mid-job.
