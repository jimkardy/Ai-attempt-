/*
 * abi_gate.c — Debroid ABI marker.
 *
 * This file is compiled by CI into lib/arm64-v8a/libdebroid-abi.so and nothing
 * else. Its only job is to make the APK carry an arm64-v8a native library, so
 * Android refuses to install Debroid on 32-bit, x86 or x86_64 devices instead of
 * installing it and failing at boot. It is MIT licensed like the rest of the app.
 */
static const char debroid_abi_marker[] = "arm64-v8a-only";

__attribute__((visibility("default"))) const char *debroid_abi(void) {
    return debroid_abi_marker;
}
