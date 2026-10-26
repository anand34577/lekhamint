// Runs the two OCR models and nothing else: finding text (a probability map) and reading one line of text
// (the most likely character at each step). Finding boxes, cropping and turning steps into text happen in
// Kotlin (app.kosh.ocr), where they are easy to test.
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <mutex>
#include <vector>

#include "net.h"

namespace {
ncnn::Net det;
ncnn::Net rec;
bool ready = false;
std::mutex lock;

bool load(ncnn::Net& net, AAssetManager* mgr, const char* param, const char* bin, int threads) {
    net.clear();
    net.opt.use_vulkan_compute = false;
    net.opt.num_threads = threads;
    net.opt.lightmode = true;
    net.opt.use_fp16_storage = true;
    net.opt.use_fp16_arithmetic = false; // full precision maths: fp16 arithmetic costs accuracy on some phones
    return net.load_param(mgr, param) == 0 && net.load_model(mgr, bin) == 0;
}
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_app_kosh_ocr_NativeOcr_init(JNIEnv* env, jobject, jobject assets, jint threads) {
    std::lock_guard<std::mutex> g(lock);
    AAssetManager* mgr = AAssetManager_fromJava(env, assets);
    ready = load(det, mgr, "ocr/det.param", "ocr/det.bin", threads) && load(rec, mgr, "ocr/rec.param", "ocr/rec.bin", threads);
    return ready ? JNI_TRUE : JNI_FALSE;
}

// The text probability map (height x width floats, 0..1) for [bitmap] scaled to width x height.
JNIEXPORT jfloatArray JNICALL Java_app_kosh_ocr_NativeOcr_detect(JNIEnv* env, jobject, jobject bitmap, jint width, jint height) {
    std::lock_guard<std::mutex> g(lock);
    if (!ready) return nullptr;
    ncnn::Mat in = ncnn::Mat::from_android_bitmap_resize(env, bitmap, ncnn::Mat::PIXEL_RGBA2BGR, width, height);
    if (in.empty()) return nullptr;
    // PaddleOCR detection: BGR, ImageNet mean and deviation.
    const float mean[3] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
    const float norm[3] = {1.f / (0.229f * 255.f), 1.f / (0.224f * 255.f), 1.f / (0.225f * 255.f)};
    in.substract_mean_normalize(mean, norm);
    ncnn::Extractor ex = det.create_extractor();
    ex.input("in0", in);
    ncnn::Mat out;
    if (ex.extract("out0", out) != 0 || out.empty()) return nullptr;
    const int n = out.w * out.h;
    jfloatArray result = env->NewFloatArray(n);
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, n, out.channel(0));
    return result;
}

// One line of text: [bitmap] is already 48 px high. Returns pairs of (class index, probability) per step.
JNIEXPORT jfloatArray JNICALL Java_app_kosh_ocr_NativeOcr_recognize(JNIEnv* env, jobject, jobject bitmap) {
    std::lock_guard<std::mutex> g(lock);
    if (!ready) return nullptr;
    ncnn::Mat in = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGBA2BGR);
    if (in.empty()) return nullptr;
    const float mean[3] = {127.5f, 127.5f, 127.5f};
    const float norm[3] = {1.f / 127.5f, 1.f / 127.5f, 1.f / 127.5f};
    in.substract_mean_normalize(mean, norm);
    ncnn::Extractor ex = rec.create_extractor();
    ex.input("in0", in);
    ncnn::Mat out;
    if (ex.extract("out0", out) != 0 || out.empty()) return nullptr;
    // out: w = classes, h = steps (already softmaxed).
    const int steps = out.h;
    const int classes = out.w;
    std::vector<float> best(steps * 2);
    for (int t = 0; t < steps; t++) {
        const float* p = out.row(t);
        int arg = 0;
        float max = p[0];
        for (int k = 1; k < classes; k++) {
            if (p[k] > max) { max = p[k]; arg = k; }
        }
        best[t * 2] = static_cast<float>(arg);
        best[t * 2 + 1] = max;
    }
    jfloatArray result = env->NewFloatArray(steps * 2);
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, steps * 2, best.data());
    return result;
}

JNIEXPORT void JNICALL Java_app_kosh_ocr_NativeOcr_release(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(lock);
    det.clear();
    rec.clear();
    ready = false;
}

}  // extern "C"
