package com.moongames.dynamicisland

import android.app.Activity
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast

/** Small reply box that drops down from the island, so you can answer without leaving your app. */
class ReplyActivity : Activity() {

    companion object {
        @Volatile
        var pendingAction: Notification.Action? = null

        @Volatile
        var pendingTitle: String = ""

        @Volatile
        var pendingIcon: Drawable? = null
    }

    private var action: Notification.Action? = null
    private lateinit var card: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = pendingAction
        if (a == null || a.remoteInputs.isNullOrEmpty()) {
            finish()
            return
        }
        action = a

        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        val root = FrameLayout(this)
        root.setOnClickListener { finish() }

        card = FrameLayout(this)
        card.background = roundRect(Color.BLACK, 30f.dpf)
        card.isClickable = true
        val col = vbox()
        col.setPadding(16.dp, 14.dp, 14.dp, 14.dp)

        val head = hbox()
        val icon = pendingIcon
        if (icon != null) {
            val iv = ImageView(this)
            iv.setImageDrawable(icon)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.roundCorners(14f.dpf)
            head.addView(iv, llp(28.dp, 28.dp).apply { marginEnd = 10.dp })
        }
        val who = pendingTitle.ifBlank { a.title?.toString() ?: "Reply" }
        head.addView(label("Reply to $who", 15f, Color.WHITE, bold = true), llp(0, WRAP, 1f))
        col.addView(head, llp(MATCH, WRAP))

        val row = hbox(Gravity.BOTTOM)
        row.setPadding(0, 12.dp, 0, 0)
        val edit = EditText(this)
        edit.hint = a.remoteInputs?.firstOrNull()?.label?.toString()?.ifBlank { null } ?: "Message"
        edit.setHintTextColor(C.GRAY)
        edit.setTextColor(Color.WHITE)
        edit.textSize = 16f
        edit.background = roundRect(C.CARD2, 20f.dpf)
        edit.setPadding(14.dp, 10.dp, 14.dp, 10.dp)
        edit.minHeight = 42.dp
        edit.maxLines = 4
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        edit.imeOptions = EditorInfo.IME_ACTION_SEND
        row.addView(edit, llp(0, WRAP, 1f))

        val send = FrameLayout(this)
        send.background = roundRect(C.BLUE, 50f.dpf)
        val sendIcon = ImageView(this)
        sendIcon.setImageResource(R.drawable.ic_send)
        send.addView(sendIcon, flp(20.dp, 20.dp, Gravity.CENTER))
        send.setOnClickListener { send(edit.text.toString()) }
        row.addView(send, llp(42.dp, 42.dp).apply { marginStart = 8.dp })
        col.addView(row, llp(MATCH, WRAP))

        edit.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) {
                send(edit.text.toString())
                true
            } else {
                false
            }
        }

        card.addView(col, flp(MATCH, WRAP))
        root.addView(card, flp(MATCH, WRAP, Gravity.TOP).apply {
            leftMargin = 8.dp
            rightMargin = 8.dp
            topMargin = 40.dp
        })
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            } else {
                insets.systemWindowInsetTop
            }
            val p = card.layoutParams as FrameLayout.LayoutParams
            p.topMargin = top + 6.dp
            card.layoutParams = p
            insets
        }
        setContentView(root)

        // Drop down from the island.
        card.alpha = 0f
        card.scaleX = 0.92f
        card.scaleY = 0.6f
        card.pivotY = 0f
        card.post {
            card.pivotX = card.width / 2f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Spring(0.85f)).start()
        }
        edit.requestFocus()
    }

    private fun send(text: String) {
        val a = action ?: return finish()
        val msg = text.trim()
        if (msg.isEmpty()) return
        val inputs = a.remoteInputs
        if (inputs.isNullOrEmpty()) {
            finish()
            return
        }
        try {
            val intent = Intent()
            val results = Bundle()
            for (ri in inputs) results.putCharSequence(ri.resultKey, msg)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            a.actionIntent.send(this, 0, intent)
            IslandService.instance?.showToast(
                ToastItem(
                    ToastStyle.BANNER, title = "", value = "Sent",
                    icon = tinted(R.drawable.ic_check, C.GREEN), accent = C.GREEN, duration = 1600L
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't send that reply", Toast.LENGTH_SHORT).show()
        }
        pendingAction = null
        pendingIcon = null
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}
