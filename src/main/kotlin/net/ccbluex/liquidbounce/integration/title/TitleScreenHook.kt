package net.ccbluex.liquidbounce.integration.title

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screens.TitleScreen

object TitleScreenHook {
    @Volatile
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (client.gui.screen() is TitleScreen) {
                client.gui.setScreen(CustomTitleScreen())
            }
        })
    }
}
