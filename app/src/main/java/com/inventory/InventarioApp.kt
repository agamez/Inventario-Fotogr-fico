package com.inventory

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Plain application: images are loaded from their files straight through
 * Coil's built-in File support, so no custom ImageLoader (and no BLOB
 * plumbing) is needed anymore.
 */
@HiltAndroidApp
class InventarioApp : Application()
