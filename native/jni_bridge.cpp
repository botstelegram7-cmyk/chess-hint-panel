/*
 * Stockfish <-> Java bridge.
 *
 * Stockfish normally talks UCI on stdin/stdout of its own process.  On Android that
 * would mean executing a binary, which newer Android versions restrict.  Instead we
 * compile the whole engine into a shared library (libstockfish.so), load it with
 * System.loadLibrary() - which is the officially supported way - and feed the UCI
 * loop through two custom stream buffers:
 *
 *     Java  --nativeWrite(cmd)-->  InQueue  --> std::cin  --> UCI::loop
 *     UCI::loop --> std::cout --> OutQueue --> Java.onEngineLine(line)
 *
 * The engine runs on its own thread inside the app process; nothing is ever
 * executed from disk and nothing leaves the phone.
 */
#include <jni.h>

#include <condition_variable>
#include <cstring>
#include <deque>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>

#include "bitboard.h"
#include "endgame.h"
#include "position.h"
#include "search.h"
#include "thread.h"
#include "tt.h"
#include "uci.h"
#include "syzygy/tbprobe.h"

namespace PSQT { void init(); }

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "chesshint-sf", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { std::fprintf(stdout, __VA_ARGS__); std::fprintf(stdout, "\n"); std::fflush(stdout); } while (0)
#endif

static JavaVM*   g_vm = nullptr;
static jobject   g_obj = nullptr;
static jmethodID g_midLine = nullptr;
static bool      g_started = false;

// ----------------------------------------------------------------- input buffer

class InQueue : public std::streambuf {
public:
    void push(const std::string& s) {
        std::lock_guard<std::mutex> lk(m);
        for (char c : s) buf.push_back(c);
        if (buf.empty() || buf.back() != '\n') buf.push_back('\n');
        cv.notify_all();
    }
protected:
    int_type underflow() override {
        std::unique_lock<std::mutex> lk(m);
        while (buf.empty() && !closed_) cv.wait(lk);
        if (buf.empty()) return traits_type::eof();
        ch = buf.front();
        buf.pop_front();
        setg(&ch, &ch, &ch + 1);
        return traits_type::to_int_type(ch);
    }
private:
    std::mutex m;
    std::condition_variable cv;
    std::deque<char> buf;
    char ch = 0;
    bool closed_ = false;
};

// ----------------------------------------------------------------- output buffer

static void javaLine(const std::string& line);

class OutQueue : public std::streambuf {
protected:
    int_type overflow(int_type c) override {
        if (traits_type::eq_int_type(c, traits_type::eof())) return traits_type::not_eof(c);
        char ch = traits_type::to_char_type(c);
        if (ch == '\n') { javaLine(line); line.clear(); }
        else if (ch != '\r') line.push_back(ch);
        return c;
    }
    int sync() override {
        if (!line.empty()) { javaLine(line); line.clear(); }
        return 0;
    }
private:
    std::string line;
};

static InQueue  g_in;
static OutQueue g_out;

static void javaLine(const std::string& line) {
    if (!g_vm || !g_obj || !g_midLine) return;
    static thread_local JNIEnv* env = nullptr;
    if (!env) {
#ifdef __ANDROID__
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
#else
        if (g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
#endif
    }
    jstring s = env->NewStringUTF(line.c_str());
    if (!s) return;
    env->CallVoidMethod(g_obj, g_midLine, s);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(s);
}

// ----------------------------------------------------------------- engine thread

static void engineMain() {
    std::cin.rdbuf(&g_in);
    std::cout.rdbuf(&g_out);
    std::cerr.rdbuf(&g_out);

    UCI::init(Options);
    PSQT::init();
    Bitboards::init();
    Position::init();
    Bitbases::init();
    Endgames::init();
    Threads.set(Options["Threads"]);
    Search::clear();

    UCI::loop(1, nullptr);      // reads commands from std::cin (our queue)

    Threads.set(0);
    LOGI("stockfish: uci loop finished");
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_chesshint_panel_UciEngine_nativeInit(JNIEnv* env, jobject thiz) {
    if (g_started) return;
    g_started = true;
    if (!g_obj) g_obj = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    g_midLine = env->GetMethodID(cls, "onEngineLine", "(Ljava/lang/String;)V");
    std::thread t(engineMain);
    t.detach();
}

JNIEXPORT void JNICALL
Java_com_chesshint_panel_UciEngine_nativeWrite(JNIEnv* env, jobject thiz, jstring cmd) {
    if (!cmd) return;
    const char* c = env->GetStringUTFChars(cmd, nullptr);
    if (c) {
        g_in.push(c);
        env->ReleaseStringUTFChars(cmd, c);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_chesshint_panel_UciEngine_nativeIsRunning(JNIEnv* env, jobject thiz) {
    return g_started ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
