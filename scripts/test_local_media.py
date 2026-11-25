"""End-to-end CPU inference against the exact catalog files. Requires Pillow and ~6 GB of disk."""
import hashlib
import pathlib
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

from PIL import Image, ImageStat

runner = pathlib.Path(sys.argv[1]).resolve()
work = pathlib.Path(sys.argv[2]).resolve()
work.mkdir(parents=True, exist_ok=True)
assets = [
    ("second-state/stable-diffusion-v1-5-GGUF", "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf", "b8944e9fe0b69b36ae1b5bb0185b3a7b8ef14347fe0fa9af6c64c4829022261f"),
    ("calcuis/wan-1.3b-gguf", "wan2.1-v3-vace-1.3b-q4_0.gguf", "c9d2ad33427cef0e485c0c32b105c3cca8a33f6bfd8249b41b9685756aed4a74"),
    ("city96/umt5-xxl-encoder-gguf", "umt5-xxl-encoder-Q3_K_S.gguf", "f64f8d6dc4d8a24276df69d0ccea789aae686f7417950a41e6568c30cb478a5c"),
    ("Comfy-Org/Wan_2.1_ComfyUI_repackaged", "split_files/vae/wan_2.1_vae.safetensors", "2fc39d31359a4b0a64f55876d8ff7fa8d780956ae2cb13463b0223e15148976b"),
]

def download(asset):
    repo, name, sha = asset
    path = work / pathlib.Path(name).name
    subprocess.run(["curl", "-fsSL", "--retry", "3", f"https://huggingface.co/{repo}/resolve/main/{name}", "-o", path], check=True)
    with path.open("rb") as f:
        if hashlib.file_digest(f, "sha256").hexdigest() != sha:
            raise ValueError(f"Invalid model checksum: {name}")
    return path

def generate(label, prompt, components):
    is_video = label != "image"
    side = 256 if is_video else 384
    count = 9 if is_video else 1
    raw = work / (label + ".rgb")
    subprocess.run([runner, "video" if is_video else "image", raw, *components],
                   input=prompt+"\n", text=True, check=True, timeout=1800)
    data = raw.read_bytes()
    frame_size = side * side * 3
    if len(data) != count * frame_size:
        raise ValueError("Incomplete frames")
    frames = [Image.frombytes("RGB", (side, side), data[i:i+frame_size]) for i in range(0, len(data), frame_size)]
    if any(max(ImageStat.Stat(frame).stddev) < 2 for frame in frames):
        raise ValueError("Empty or constant frame")
    if is_video:
        if len({frame.tobytes() for frame in frames}) != count:
            raise ValueError("Repeated identical video frames")
        frames[0].save(work / (label + ".gif"), save_all=True, append_images=frames[1:], duration=167, loop=0)
    else:
        frames[0].save(work / "image.png")
        (work / "reference.rgb").write_bytes(frames[0].resize((256,256)).tobytes())
    print(f"Verified {label}: {count} frames at {side} x {side}", flush=True)

with ThreadPoolExecutor(4) as pool:
    picture, *video = pool.map(download, assets)
generate("image", "A small red sailboat on a sunny blue lake, photograph", [picture])
generate("video", "A red sailboat moves across a sunny lake, water ripples", video)
generate("video-reference", "The sailboat drifts slowly on the lake", video + [work / "reference.rgb"])
