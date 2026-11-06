package io.github.tonyispht.signalopsirl

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN that has to be entered before the target list can be viewed or changed.
 *
 * Only a salted PBKDF2 hash of the PIN is stored. Wrong guesses are slowed down (3 free tries, then a growing wait).
 * This is a lock on the app's screens: it keeps the list from being read over your shoulder or by someone using your
 * unlocked phone. It does not encrypt the data on the phone. A forgotten PIN can only be cleared by clearing the
 * app's data (which also deletes the target list).
 */
object PinLock {
    private const val K_HASH = "pinHash"
    private const val K_SALT = "pinSalt"
    private const val K_FAILS = "pinFails"
    private const val K_LOCK = "pinLockUntil"
    const val MIN_LEN = 4
    const val MAX_LEN = 8
    private const val ITER = 60_000

    fun isSet(store: Store): Boolean = store.prefs.contains(K_HASH)

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITER, 256)).encoded

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun set(store: Store, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.prefs.edit()
            .putString(K_SALT, hex(salt)).putString(K_HASH, hex(hash(pin, salt)))
            .putInt(K_FAILS, 0).putLong(K_LOCK, 0L).apply()
    }

    fun clear(store: Store) {
        store.prefs.edit().remove(K_HASH).remove(K_SALT).remove(K_FAILS).remove(K_LOCK).apply()
    }

    /** Milliseconds left before another guess is allowed (0 = guess now). */
    fun waitMs(store: Store): Long = (store.prefs.getLong(K_LOCK, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** True when [pin] is right. A wrong guess counts toward the wait; while waiting, every guess is refused. */
    fun verify(store: Store, pin: String): Boolean {
        if (waitMs(store) > 0L) return false
        val h = store.prefs.getString(K_HASH, null) ?: return true
        val s = store.prefs.getString(K_SALT, null) ?: return false
        val ok = runCatching { MessageDigest.isEqual(unhex(h), hash(pin, unhex(s))) }.getOrDefault(false)
        val e = store.prefs.edit()
        if (ok) {
            e.putInt(K_FAILS, 0).putLong(K_LOCK, 0L)
        } else {
            val fails = store.prefs.getInt(K_FAILS, 0) + 1
            e.putInt(K_FAILS, fails)
            if (fails >= 3) {
                val secs = minOf(300L, 5L shl minOf(fails - 3, 6))
                e.putLong(K_LOCK, System.currentTimeMillis() + secs * 1000L)
            }
        }
        e.apply()
        return ok
    }
}

/**
 * Asks for a PIN. [onSubmit] returns an error text to show (the dialog stays open) or null when it was accepted
 * (the caller then closes or changes the dialog).
 */
@Composable
fun PinDialog(
    title: String,
    hint: String,
    onSubmit: (String) -> String?,
    onCancel: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var pin by remember(title) { mutableStateOf("") }
    var error by remember(title) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(title) { runCatching { focus.requestFocus() } }

    fun submit() {
        if (pin.length < PinLock.MIN_LEN) {
            error = "USE ${PinLock.MIN_LEN} TO ${PinLock.MAX_LEN} DIGITS"
            return
        }
        error = onSubmit(pin)
        if (error != null) pin = ""
    }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = cs.surface,
        shape = HudShape,
        title = { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, fontSize = 10.sp, color = cs.onSurfaceVariant)
                BasicTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(PinLock.MAX_LEN); error = null },
                    textStyle = TextStyle(color = cs.onSurface, fontFamily = Mono, fontSize = 22.sp, letterSpacing = 6.sp),
                    cursorBrush = SolidColor(cs.primary),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxWidth()
                                .border(1.dp, if (error != null) cs.error else cs.outline, BtnShape)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) { inner() }
                    }
                )
                error?.let { Text(it, fontSize = 10.sp, color = cs.error) }
            }
        },
        confirmButton = { MiniButton("OK") { submit() } },
        dismissButton = { MiniButton("CANCEL", cs.onSurfaceVariant) { onCancel() } }
    )
}
