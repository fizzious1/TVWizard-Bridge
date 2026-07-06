package ai.djwizard.tvbridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// One-tap self-updater (Q3 Phase 3.3).
//
// Flow: GET /bridge/update → strict-semver compare against
// BuildConfig.VERSION_NAME → download the APK into app cache with progress →
// verify BOTH the byte-level SHA-256 from the manifest AND the APK's signing
// certificate against the hardcoded pin below → hand the file to the system
// PackageInstaller → exactly one "install update?" system dialog.
//
// Failure discipline: any hash/pin/size mismatch deletes the download and
// surfaces UpdateState.Error. Nothing is ever handed to the installer unless
// both checks passed on the exact bytes we are about to install.
class UpdateManager(context: Context) {

    private val app = context.applicationContext

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Manifest is the relay's release descriptor from GET /bridge/update.
    // cert_sha256 is deliberately NOT modeled: per the Phase 3.3 security
    // contract it is advisory only, and reading a pin out of the fetched
    // manifest would be self-referential — see SIGNING_CERT_SHA256.
    data class Manifest(
        val version: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val notes: String,
    )

    // checkQuietly is the passive staleness probe: fetch the manifest once
    // (activity start), and if a newer version is published surface it as
    // Idle(availableVersion). Network errors are swallowed — this must never
    // interrupt pairing UX.
    suspend fun checkQuietly() = withContext(Dispatchers.IO) {
        if (state.value !is UpdateState.Idle) return@withContext
        val manifest = runCatching { fetchManifest() }.getOrNull() ?: return@withContext
        if (SemVer.isNewer(manifest.version, BuildConfig.VERSION_NAME) == true &&
            state.value is UpdateState.Idle
        ) {
            state.value = UpdateState.Idle(availableVersion = manifest.version)
        }
    }

    // checkAndInstall runs the full user-initiated flow. The caller must have
    // already handled the "install unknown apps" permission (MainActivity does).
    suspend fun checkAndInstall() = withContext(Dispatchers.IO) {
        try {
            state.value = UpdateState.Checking
            val manifest = fetchManifest()
            val newer = SemVer.isNewer(manifest.version, BuildConfig.VERSION_NAME)
            if (newer == null) {
                // Fail closed: a malformed version is never comparable.
                throw IOException(
                    "malformed version in manifest: " +
                        "remote=${manifest.version} local=${BuildConfig.VERSION_NAME}"
                )
            }
            if (!newer) {
                state.value = UpdateState.UpToDate
                return@withContext
            }

            Log.i(TAG, "update ${BuildConfig.VERSION_NAME} -> ${manifest.version} (${manifest.sizeBytes} bytes)")
            val apk = download(manifest)
            try {
                state.value = UpdateState.Verifying
                verify(apk, manifest)
                state.value = UpdateState.AwaitingConfirm
                commitInstall(apk)
            } finally {
                // The PackageInstaller session holds its own staged copy after
                // commit — and on any verification failure the download must
                // not survive to be installed by anything else.
                apk.delete()
            }
        } catch (e: CancellationException) {
            // UI scope died mid-flow (activity destroyed). Reset the shared
            // state so a recreated activity doesn't render a frozen step.
            state.value = UpdateState.Idle()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update failed: ${e.message}")
            state.value = UpdateState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fetchManifest(): Manifest {
        val req = Request.Builder().url(BuildConfig.RELAY_URL + UPDATE_PATH).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("bridge/update: HTTP ${resp.code}")
            val j = JSONObject(body)
            return Manifest(
                version = j.getString("version"),
                url = j.getString("url"),
                sha256 = j.getString("sha256"),
                sizeBytes = j.optLong("size_bytes", -1L),
                notes = j.optString("notes", ""),
            )
        }
    }

    // download streams the APK to a private cache file, emitting whole-percent
    // progress. The advertised size is enforced as an exact bound — a body
    // that overshoots or undershoots it fails before verification even runs.
    private suspend fun download(manifest: Manifest): File {
        if (!manifest.url.startsWith(HTTPS_PREFIX)) {
            throw IOException("refusing non-HTTPS APK URL")
        }
        if (manifest.sizeBytes <= 0 || manifest.sizeBytes > MAX_APK_BYTES) {
            throw IOException("implausible size_bytes ${manifest.sizeBytes}")
        }

        val dir = File(app.cacheDir, UPDATE_DIR)
        dir.deleteRecursively() // drop any stale download from a previous run
        if (!dir.mkdirs()) throw IOException("cannot create ${dir.path}")
        val out = File(dir, APK_FILE)

        state.value = UpdateState.Downloading(0)
        val req = Request.Builder().url(manifest.url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("APK download: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("APK download: empty body")
            var read = 0L
            var lastPercent = 0
            body.byteStream().use { input ->
                out.outputStream().use { fileOut ->
                    val buf = ByteArray(DOWNLOAD_CHUNK_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                        if (read > manifest.sizeBytes) {
                            throw IOException("download exceeds advertised size_bytes ${manifest.sizeBytes}")
                        }
                        fileOut.write(buf, 0, n)
                        val pct = ((read * 100) / manifest.sizeBytes).toInt()
                        if (pct != lastPercent) {
                            lastPercent = pct
                            state.value = UpdateState.Downloading(pct)
                        }
                    }
                }
            }
            if (read != manifest.sizeBytes) {
                throw IOException("download size mismatch: got $read, manifest says ${manifest.sizeBytes}")
            }
        }
        return out
    }

    // verify enforces the two independent checks from the Phase 3.3 spec on
    // the exact at-rest bytes that will be handed to the installer:
    //   (a) SHA-256 of the file equals the manifest's sha256, and
    //   (b) the APK's signing certificate equals the HARDCODED pin.
    // Plus a package-name sanity check so a correctly-signed-but-wrong APK
    // can't be offered as "the bridge".
    private fun verify(apk: File, manifest: Manifest) {
        val actual = sha256HexOfFile(apk)
        val expected = normalizeHexDigest(manifest.sha256)
        if (actual != expected) {
            throw SecurityException("APK SHA-256 mismatch (expected $expected, got $actual)")
        }

        val info = packageArchiveInfo(apk)
        if (info.packageName != BuildConfig.APPLICATION_ID) {
            throw SecurityException("APK package ${info.packageName} is not ${BuildConfig.APPLICATION_ID}")
        }

        val certs = signerCertDigests(apk)
        if (certs.size != 1 || certs[0] != SIGNING_CERT_SHA256) {
            throw SecurityException("APK signing cert does not match the pinned TVWizard release cert")
        }
    }

    private fun packageArchiveInfo(apk: File): PackageInfo =
        app.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw SecurityException("not a parseable APK")

    // signerCertDigests returns the SHA-256 (lowercase hex, no colons) of each
    // signing certificate in the archive. API 28+ uses SigningInfo; older
    // devices (minSdk 23) fall back to the deprecated GET_SIGNATURES — safe
    // here because the full-file hash already proved these are the exact
    // published bytes, so the cert check is pinning, not parsing untrusted
    // input for authenticity.
    private fun signerCertDigests(apk: File): List<String> {
        val pm = app.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info?.signingInfo
            if (signingInfo != null) {
                val signers =
                    if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
                    else signingInfo.signingCertificateHistory
                return signers.orEmpty().map { sha256Hex(it.toByteArray()) }
            }
            // Some API 28-era builds leave signingInfo null for archives;
            // fall through to the legacy path rather than failing open/closed
            // on a platform quirk.
        }
        @Suppress("DEPRECATION")
        val legacy = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        return legacy?.signatures.orEmpty().map { sha256Hex(it.toByteArray()) }
    }

    // commitInstall stages the verified APK into a PackageInstaller session and
    // commits it. The status callback lands in UpdateReceiver, which fires the
    // single system confirm dialog (STATUS_PENDING_USER_ACTION) — the one tap.
    private fun commitInstall(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(BuildConfig.APPLICATION_ID)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(APK_FILE, 0, apk.length()).use { sessionOut ->
                apk.inputStream().use { it.copyTo(sessionOut) }
                session.fsync(sessionOut)
            }
            val statusIntent = Intent(app, UpdateReceiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The installer mutates the intent to attach EXTRA_STATUS /
                // EXTRA_INTENT, so the PendingIntent must be mutable.
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val pending = PendingIntent.getBroadcast(app, sessionId, statusIntent, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun sha256HexOfFile(f: File): String {
        val md = MessageDigest.getInstance(SHA_256)
        f.inputStream().use { input ->
            val buf = ByteArray(DOWNLOAD_CHUNK_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { HEX_FORMAT.format(it) }
    }

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance(SHA_256).digest(b).joinToString("") { HEX_FORMAT.format(it) }

    // normalizeHexDigest maps both digest spellings in the wild — the
    // manifest's bare-hex sha256 and keytool's colon-separated fingerprint —
    // onto lowercase bare hex.
    private fun normalizeHexDigest(s: String): String =
        s.replace(":", "").trim().lowercase()

    companion object {
        private const val TAG = "TVBridgeUpdate"

        // SECURITY CONTRACT (Q3 Phase 3.3): this pin is a COMPILE-TIME
        // constant by design. The manifest's cert_sha256 field is ADVISORY
        // ONLY — a pin read from the fetched manifest is self-referential and
        // worthless (whoever tampers with the APK/manifest controls that field
        // too). Verified 2026-07-06 against three independent sources:
        //   (1) the signing cert extracted from the published v0.6.4 release
        //       APK itself (openssl pkcs7 -print_certs → x509 -fingerprint -sha256),
        //   (2) the live relay manifest at https://tv.djwizard.ai/bridge/update,
        //   (3) docs/plans/features/007-release-signing.md in the relay repo
        //       (keystore CN=TVWizard, O=DJWizard).
        // Rotating the signing key requires shipping a bridge with the new pin
        // BEFORE publishing a release signed by it.
        const val SIGNING_CERT_SHA256 =
            "90def7bc5db7beb48606f226706cc29b34137ddebe29f738a1fa0cbe12eee0f0"

        // Broadcast action for PackageInstaller status callbacks (UpdateReceiver).
        const val ACTION_INSTALL_STATUS = "ai.djwizard.tvbridge.INSTALL_STATUS"

        private const val UPDATE_PATH = "/bridge/update"
        private const val UPDATE_DIR = "updates"
        private const val APK_FILE = "bridge-update.apk"
        private const val HTTPS_PREFIX = "https://"
        private const val SHA_256 = "SHA-256"
        private const val HEX_FORMAT = "%02x"
        private const val DOWNLOAD_CHUNK_BYTES = 64 * 1024

        // Sanity ceiling: the bridge APK is ~6 MB; anything near this bound is
        // a broken or hostile manifest, not a release.
        private const val MAX_APK_BYTES = 200L * 1024 * 1024

        // Shared with UpdateReceiver (which has no UpdateManager reference) —
        // same pattern as TVAccessibilityService.state.
        val state = MutableStateFlow<UpdateState>(UpdateState.Idle())
    }
}
