// AIDL for the Shizuku UserService. The service runs in a process spawned by
// the Shizuku server with shell uid (2000), so calls made from inside it can
// inject input events the bridge itself cannot. Mirror any change in
// ShizukuUserService.kt and ShizukuController.kt.
package ai.djwizard.tvbridge;

interface IUserService {
    // destroy() carries the reserved transaction id the Shizuku server uses to
    // tear the service process down. Must keep this exact id.
    void destroy() = 16777114;

    void exit() = 1;

    // Injects a single key event via `input keyevent <keyCode>` running as
    // shell uid. Returns the process exit code (0 = success).
    int sendKeyEvent(int keyCode) = 2;
}
