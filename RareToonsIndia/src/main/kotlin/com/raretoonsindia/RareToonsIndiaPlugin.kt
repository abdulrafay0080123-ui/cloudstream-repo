package com.raretoonsindia

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RareToonsIndiaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RareToonsIndiaProvider())
    }
}
