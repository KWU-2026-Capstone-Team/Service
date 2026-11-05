package com.verivid.app.data

import android.app.Application
import com.verivid.app.BuildConfig

/** Both features share one session and one session-creation mutex. */
internal object ServiceConnection {
    private var connection: DeeptectorApi? = null
    @Synchronized fun get(application: Application): DeeptectorApi = connection ?: DeeptectorApi(
        BuildConfig.API_BASE_URL, EncryptedSessionStore(application, BuildConfig.API_BASE_URL)
    ).also { connection = it }
}
