# LocalPaint WebGPU inpainting proof of concept

An install-free, mobile-first static website. It uses **ONNX Runtime Web + WebGPU** to run [MI-GAN](https://huggingface.co/lxfater/inpaint-web/blob/main/migan.onnx) (29.5 MB) **inside the browser**. Images and masks are not uploaded to any server. Initial model weights and the ONNX Runtime script are downloaded once from external providers; browsers may cache them locally afterwards.

**This is not Stable Diffusion 1.5.** MI-GAN performs single-pass, **prompt-free** object/region removal. It is intentionally small and suitable to test local WebGPU inpainting on a Snapdragon 8 Gen 2 before attempting full Stable Diffusion models.

## Features

- Browser WebGPU availability and device limit check
- Paint mask with touch/stylus, erase, undo, clear
- Import JPEG/PNG/other browser-decodable images and save PNG output
- Local ONNX inference with MI-GAN using WebGPU; optional manual WASM CPU fallback
- Checkpoint download progress and cache (Cache Storage API)
- ONNX model import (expects the same MI-GAN model topology)
- **ColdFlesh `.safetensors` file inspector**: reads only the JSON header, not the 1 GB+ tensors, and describes model tensor counts, dtypes and likely UNet input channels

## How to try it on your Android phone

**Use a secure HTTPS URL.** WebGPU is usually not available when loading `file:///…` or an insecure `http://phone-ip/…` page.

1. Upload this directory to any static HTTPS host (Cloudflare Pages, Netlify, GitHub Pages), keeping `index.html`, `app.js`, `styles.css` together.
2. In current **Chrome** on Android, open the hosted page. Look for **WebGPU available**.
3. Click **Load MI-GAN model** and allow about **29.5 MB** to download. You may instead download `migan.onnx` and click **Use ONNX file**. Wait for **MI-GAN ready · WEBGPU**.
4. Choose a photo or sample and paint the area red.
5. Click **Run local inpainting**. This is **one MI-GAN inference pass**, not an SD diffusion loop; it has an elapsed timer rather than a sampling step count.
6. Save the PNG or tap **Edit this result**.
7. To inspect ColdFlesh, tap **Inspect safetensors locally**. This only reads the model metadata header; it does **not** load or run the checkpoint.

Use Chrome rather than a browser's embedded in-app webview. If GPU is unavailable, you may use the **WebAssembly CPU fallback**, but that does not test WebGPU.

## Development and limitations

No npm, bundler, or build step is needed. Serve the directory using HTTPS. For desktop localhost development, `python3 -m http.server 8000` and visit `http://localhost:8000`.

- The image is *contained* in a **512 × 512** square with a plain padded background; resulting PNG is 512 × 512.
- MI-GAN's published ONNX model expects float32 `[1,4,512,512]`, with the first channel equal to **mask keep - 0.5** and RGB multiplied by keep after normalization to `[-1,1]`. Output is float32 RGB `[-1,1]`. Only masked pixels are composited back onto the original.
- No prompt-driven generation, no LoRAs, no LCM sampler, no support for directly loading `.safetensors` as ONNX.
- WebGPU runtime and operators may not work on every phone/driver. If a model session fails to load, the user sees the error; nothing silently falls back to CPU.
- A checkpoint around 1 GB is not automatically lightweight in browser memory: the model requires conversion to browser-supported ONNX graphs, scheduler, text encoder and VAE, plus GPU weights and activations. On-device GPU memory capacity may be smaller than browser storage.
- This prototype isn't validated on a physical Snapdragon 8 Gen 2, and a successful static page build is not proof of successful inference.

## External dependencies

- [onnxruntime-web 1.30.0](https://www.npmjs.com/package/onnxruntime-web), hosted from jsDelivr via `ort.webgpu.min.js`
- [MI-GAN ONNX file](https://huggingface.co/lxfater/inpaint-web/resolve/main/migan.onnx), ~29.5 MB
- MI-GAN architecture / weights based on [Picsart AI Research MI-GAN](https://github.com/Picsart-AI-Research/MI-GAN) (MIT license). Model distribution terms should be checked for the mirrored ONNX file before any commercial distribution.

## Privacy

All image editing and inference happens in the browser. The application does contact jsDelivr for the runtime and Hugging Face for model weights on initial load. Inspect the Network tab if you want to audit external fetches. The application does not intentionally upload images, masks, prompts, or checkpoints.