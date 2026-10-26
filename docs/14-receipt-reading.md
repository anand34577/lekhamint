# 14 – Receipt reading (Android)

Receipts are read on the phone. Nothing uses Google Play services or the network.

## Pieces

| Part | Where | Licence |
|---|---|---|
| Text detection model: PaddleOCR PP-OCRv5 mobile det | `android/app/src/main/assets/ocr/det.param`, `det.bin` | Apache 2.0 |
| Text recognition model: PaddleOCR en PP-OCRv5 mobile rec, 436-character dictionary | `assets/ocr/rec.param`, `rec.bin`, `keys.txt` | Apache 2.0 |
| Runtime: ncnn 20260526, static Android build | downloaded by the `fetchNcnn` Gradle task, SHA-256 pinned | BSD 3-Clause |
| JNI: runs the two models and nothing else | `android/app/src/main/cpp/ocr_jni.cpp` | MIT (this project) |
| Boxes, line grouping, page tilt | `app/kosh/ocr/TextFinder.kt` | MIT |
| Total, date, shop and currency | `app/kosh/ocr/ReceiptParser.kt` | MIT |

The steps after the models run are in Kotlin so they can be unit tested (`ReceiptOcrTest`):

1. **Detect.** The photo, upright (EXIF applied), scaled to at most 960 px on the long side in multiples of 32,
   BGR with ImageNet mean and deviation. The model returns a text probability map.
2. **Find boxes.** Threshold 0.3, connected regions, the smallest rotated rectangle around each, box score at
   least 0.6, then grown by 1.5 ("unclip"), as PaddleOCR's DB post-processing does.
3. **Read.** Each box is cropped and straightened to 48 px high, scaled to (x − 127.5) / 127.5, and decoded with
   CTC greedy decoding (classes: blank + `keys.txt` + space). Lines under 0.5 confidence are dropped.
4. **Group into lines.** Boxes are turned back by the page's tilt (the median angle of the wide boxes), then
   joined when they overlap vertically, so "TOTAL" and its amount stay on one line in a slanted photo.
5. **Parse.** The total is the amount on the strongest "total" line (Grand Total and Net Payable over Total; never
   Sub Total, tax, savings or change), else the largest amount printed twice. The date must fall within the last
   two years. The shop is large text near the top that isn't an address, tax number or heading.

## Rebuilding the models

1. Download the inference models from `huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det` and
   `huggingface.co/PaddlePaddle/en_PP-OCRv5_mobile_rec`.
2. Convert to ONNX: `paddle2onnx --model_dir <m> --model_filename inference.json --params_filename
   inference.pdiparams --save_file <m>.onnx --opset_version 14` (paddlepaddle 3.1.1 with paddle2onnx 2.1.0;
   newer paddle builds fail to load on Windows).
3. Convert to ncnn: `pnnx <m>.onnx fp16=1`, keeping the dynamic input size.
4. **Make the shapes static where the bundled ncnn needs it.** Recent pnnx writes the squeeze-and-excite
   reshapes in the detector as `Reshape … 12=0 13=0 6="1,1,//(96,0n),0n"` (a batch-aware shape). ncnn
   20260526 cannot load that ("expr divide by zero", "layer load_param … failed") and the reader stops working.
   Replace each with the fixed shape `0=1 1=1 2=96` (or `2=24`); the output is identical. The unit test
   `modelsLoadOnTheBundledNcnn` fails if such a layer comes back.
5. Check both models with the same ncnn version the app ships (`pip install ncnn==1.0.20260526`) on a few
   receipt photos before replacing the assets.

Raising the ncnn version: change the version and SHA-256 in `android/app/build.gradle.kts`, rebuild, and run the
app on a phone or emulator with a receipt photo.

## Size

The models are about 6.3 MB (fp16). `libkoshocr.so` is 4.8 MB on arm64 and 2.5 MB on 32-bit ARM, stored
compressed in the APK. Release builds ship ARM only; debug builds add x86_64 for the emulator.
