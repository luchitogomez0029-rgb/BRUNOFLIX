package com.arflix.plugins.spanish

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SpanishProvidersPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineCalidadProvider())
        registerMainAPI(SmartersTvProvider())
        registerMainAPI(TioPlusProvider())
    }
}
