package com.inventory

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.inventory.image.ByteArrayFetcher
import com.inventory.image.ByteArrayKeyer
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class InventarioApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(true)
            .components {
                add(ByteArrayKeyer())
                add(ByteArrayFetcher.Factory())
            }
            .build()
    }
}
