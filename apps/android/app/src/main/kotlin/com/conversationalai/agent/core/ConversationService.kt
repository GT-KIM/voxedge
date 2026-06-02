package com.conversationalai.agent.core

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Microphone foreground service that will host the always-listening speech loop
 * (ConversationController + the four concurrent-region workers). Skeleton stub for now;
 * step 5 wires the state machine. Declared in the manifest with foregroundServiceType=microphone.
 */
class ConversationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
