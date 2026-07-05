/**
 * Bantu JNI Bridge
 *
 * This JNI library provides a way to execute the Bantu binary that bypasses
 * Android's SELinux restrictions on exec() from app-writable directories.
 *
 * How it works:
 * 1. Java loads this library via System.loadLibrary("banturun")
 * 2. Java calls execBantu() with the binary path and arguments
 * 3. This library forks a child process
 * 4. The child process calls execv() to replace itself with the Bantu binary
 * 5. The parent process reads stdout/stderr via pipes and returns output to Java
 *
 * Why this works when ProcessBuilder doesn't:
 * - ProcessBuilder uses Java's Runtime.exec() which goes through the JVM's
 *   fork+exec path. On Android 10+ with MIUI/Xiaomi SELinux policies,
 *   this path is blocked for files in app directories.
 * - This JNI approach uses direct POSIX fork()+execv() from within a loaded
 *   .so, which has a different SELinux context (unconfined_domain) because
 *   it's running as part of the app's native code.
 * - The binary being executed must still be in nativeLibraryDir (which has
 *   the right SELinux label) for this to work.
 */

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>

#define LOG_TAG "BantuJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Execute the Bantu binary with the given arguments.
 *
 * @param env       JNI environment
 * @param thiz      Java this pointer
 * @param binaryPath  absolute path to the Bantu binary (libbantu.so)
 * @param args      Java String array of arguments (e.g., ["run", "hello.b"])
 * @param workDir   working directory for the process
 * @return          process exit code, or -1 on fork/exec failure
 */
JNIEXPORT jint JNICALL
Java_com_bantu_droid_BantuBridge_nativeExec(JNIEnv *env, jobject thiz,
    jstring binaryPath, jobjectArray args, jstring workDir) {

    const char *binary = env->GetStringUTFChars(binaryPath, nullptr);
    const char *workdir = env->GetStringUTFChars(workDir, nullptr);

    // Build argv array: argv[0] = binary name, argv[1..] = args
    int argc = env->GetArrayLength(args);
    std::vector<const char*> argv;
    std::vector<std::string> argStorage;

    // argv[0] = binary name
    argStorage.push_back(binary);
    argv.push_back(argStorage.back().c_str());

    // Copy Java args
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) env->GetObjectArrayElement(args, i);
        const char *argStr = env->GetStringUTFChars(arg, nullptr);
        argStorage.push_back(argStr);
        argv.push_back(argStorage.back().c_str());
        env->ReleaseStringUTFChars(arg, argStr);
        env->DeleteLocalRef(arg);
    }

    // Null-terminate argv
    argv.push_back(nullptr);

    LOGI("nativeExec: %s in %s", binary, workdir);
    for (size_t i = 0; i < argv.size() - 1; i++) {
        LOGI("  argv[%zu] = %s", i, argv[i]);
    }

    // Ensure binary is executable
    chmod(binary, 0755);

    // Create pipes for stdout+stderr
    int pipefd[2];
    if (pipe(pipefd) == -1) {
        LOGE("pipe() failed: %s", strerror(errno));
        env->ReleaseStringUTFChars(binaryPath, binary);
        env->ReleaseStringUTFChars(workDir, workdir);
        return -1;
    }

    pid_t pid = fork();

    if (pid == -1) {
        // Fork failed
        LOGE("fork() failed: %s", strerror(errno));
        close(pipefd[0]);
        close(pipefd[1]);
        env->ReleaseStringUTFChars(binaryPath, binary);
        env->ReleaseStringUTFChars(workDir, workdir);
        return -1;
    }

    if (pid == 0) {
        // ── Child process ──

        // Close read end of pipe
        close(pipefd[0]);

        // Redirect stdout and stderr to the pipe
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);

        // Change working directory
        if (workdir != nullptr) {
            chdir(workdir);
        }

        // Execute the Bantu binary
        execv(binary, const_cast<char* const*>(argv.data()));

        // If execv returns, it failed
        // Write error to stderr (which is now the pipe)
        const char *errMsg = "execv() failed: ";
        write(STDERR_FILENO, errMsg, strlen(errMsg));
        write(STDERR_FILENO, strerror(errno), strlen(strerror(errno)));
        write(STDERR_FILENO, "\n", 1);

        _exit(127);
    }

    // ── Parent process ──

    // Close write end of pipe
    close(pipefd[1]);

    // Read child output
    char buf[4096];
    ssize_t n;
    // We just drain the pipe here; the Java side will read the process output
    // via Process.getInputStream() if we return the pid
    while ((n = read(pipefd[0], buf, sizeof(buf))) > 0) {
        // Just drain — Java reads output differently
    }
    close(pipefd[0]);

    // Wait for child
    int status = 0;
    waitpid(pid, &status, 0);

    int exitCode = WIFEXITED(status) ? WEXITSTATUS(status) : -1;

    LOGI("nativeExec: child exited with code %d", exitCode);

    env->ReleaseStringUTFChars(binaryPath, binary);
    env->ReleaseStringUTFChars(workDir, workdir);

    return exitCode;
}

/**
 * Fork+exec the Bantu binary and return the PID.
 * Output is read via the pipe file descriptors returned to Java.
 *
 * This is the streaming version — Java will read the output in a loop.
 *
 * @param env       JNI environment
 * @param thiz      Java this pointer
 * @param binaryPath  absolute path to the Bantu binary
 * @param args      Java String array of arguments
 * @param workDir   working directory
 * @return          int[3] = {pid, stdoutFd, stderrFd}, or null on failure
 */
JNIEXPORT jintArray JNICALL
Java_com_bantu_droid_BantuBridge_nativeForkExec(JNIEnv *env, jobject thiz,
    jstring binaryPath, jobjectArray args, jstring workDir) {

    const char *binary = env->GetStringUTFChars(binaryPath, nullptr);
    const char *workdir = env->GetStringUTFChars(workDir, nullptr);

    // Build argv
    int argc = env->GetArrayLength(args);
    std::vector<const char*> argv;
    std::vector<std::string> argStorage;

    argStorage.push_back(binary);
    argv.push_back(argStorage.back().c_str());

    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) env->GetObjectArrayElement(args, i);
        const char *argStr = env->GetStringUTFChars(arg, nullptr);
        argStorage.push_back(argStr);
        argv.push_back(argStorage.back().c_str());
        env->ReleaseStringUTFChars(arg, argStr);
        env->DeleteLocalRef(arg);
    }
    argv.push_back(nullptr);

    LOGI("nativeForkExec: %s", binary);

    // Ensure binary is executable
    chmod(binary, 0755);

    // Create a single pipe for merged stdout+stderr.
    // Many CLI tools (e.g., cloudflared) write all log output including
    // URLs to stderr. Merging them into one pipe ensures the Java side
    // sees ALL output from getInputStream() without needing to read
    // two separate streams.
    int outPipe[2];
    if (pipe(outPipe) == -1) {
        LOGE("pipe() failed: %s", strerror(errno));
        env->ReleaseStringUTFChars(binaryPath, binary);
        env->ReleaseStringUTFChars(workDir, workdir);
        return nullptr;
    }

    pid_t pid = fork();

    if (pid == -1) {
        LOGE("fork() failed: %s", strerror(errno));
        close(outPipe[0]); close(outPipe[1]);
        env->ReleaseStringUTFChars(binaryPath, binary);
        env->ReleaseStringUTFChars(workDir, workdir);
        return nullptr;
    }

    if (pid == 0) {
        // ── Child process ──
        close(outPipe[0]);

        // Redirect both stdout and stderr into the single pipe
        dup2(outPipe[1], STDOUT_FILENO);
        dup2(outPipe[1], STDERR_FILENO);
        close(outPipe[1]);

        // Change working directory
        if (workdir != nullptr) {
            chdir(workdir);
        }

        // Execute
        execv(binary, const_cast<char* const*>(argv.data()));

        // execv failed
        const char *errMsg = "execv() failed: ";
        write(STDERR_FILENO, errMsg, strlen(errMsg));
        write(STDERR_FILENO, strerror(errno), strlen(strerror(errno)));
        write(STDERR_FILENO, "\n", 1);
        _exit(127);
    }

    // ── Parent process ──
    close(outPipe[1]);

    LOGI("nativeForkExec: child pid=%d, mergedFd=%d", pid, outPipe[0]);

    // Return [pid, mergedFd, mergedFd] — both entries point to the same pipe
    jintArray result = env->NewIntArray(3);
    jint data[3] = {pid, outPipe[0], outPipe[0]};
    env->SetIntArrayRegion(result, 0, 3, data);

    env->ReleaseStringUTFChars(binaryPath, binary);
    env->ReleaseStringUTFChars(workDir, workdir);

    return result;
}

/**
 * Check if a file exists and is executable via access(X_OK).
 * Returns 0 = OK, -1 = not found, -2 = no execute permission.
 */
JNIEXPORT jint JNICALL
Java_com_bantu_droid_BantuBridge_nativeCheckExecutable(JNIEnv *env, jobject thiz,
    jstring filePath) {

    const char *path = env->GetStringUTFChars(filePath, nullptr);

    if (access(path, F_OK) != 0) {
        env->ReleaseStringUTFChars(filePath, path);
        return -1;  // Not found
    }

    if (access(path, X_OK) != 0) {
        // Try chmod
        chmod(path, 0755);
        if (access(path, X_OK) != 0) {
            env->ReleaseStringUTFChars(filePath, path);
            return -2;  // No execute permission even after chmod
        }
    }

    env->ReleaseStringUTFChars(filePath, path);
    return 0;  // OK
}

/**
 * Wait for a child process to exit and return its status.
 * Uses proper waitpid() instead of pipe-EOF heuristics.
 *
 * @param pid  process ID to wait for
 * @return exit code (0-255), or -1 on error (e.g., ECHILD)
 */
JNIEXPORT jint JNICALL
Java_com_bantu_droid_BantuBridge_nativeWaitForPid(JNIEnv *env, jobject thiz,
    jint pid) {
    int status = 0;
    pid_t ret = waitpid((pid_t)pid, &status, 0);
    if (ret == -1) {
        LOGE("nativeWaitForPid(%d) failed: %s", (int)pid, strerror(errno));
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}

} // extern "C"
