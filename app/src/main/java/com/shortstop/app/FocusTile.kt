package com.shortstop.app

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Quick Settings tile: start (or end) a focus session without opening the app. */
class FocusTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (Store.focusRunning(this)) {
            if (!Store.endFocusEarly(this)) {
                Toast.makeText(this, "Strict mode is on. It ends when the timer does.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Store.startFocus(this, Store.lastMinutes(this), Store.lastTag(this), Store.strictDefault(this),
                Store.lastBreaks(this), Store.lastBreakMin(this))
            Toast.makeText(this, "Focus started: ${Store.lastMinutes(this)} min", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = Store.focusRunning(this)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = if (on) "Focus · ${Store.focusLeftMin(this)}m" else "Start focus"
        t.updateTile()
    }
}
