package dev.opengarmin.app.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreTest {
    @Test
    fun savesEncryptedAndNeverStoresPlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SecretStore(context)
        store.delete()

        val hint = store.save("sk-test-1234567890")
        assertEquals("••••7890", hint)
        assertEquals("sk-test-1234567890", store.read())

        val raw = context.getSharedPreferences("pulse_secrets", Context.MODE_PRIVATE)
            .getString("provider_key_ciphertext", null)
        assertNotNull(raw)
        assertFalse(raw!!.contains("sk-test"))

        store.delete()
        assertNull(store.read())
    }
}
