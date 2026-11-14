package local.solwatch.pocket

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Date
import kotlin.system.exitProcess

/**
 * Keeps the last crash so the user can see and copy it: no adb or computer needed.
 * The report screen runs in its own process with plain Android views (no Compose, no Python),
 * so it still opens when the crash is in the app's own start-up.
 */
object CrashReport {
    const val PROCESS_SUFFIX = ":crash"

    fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file(app).writeText(
                    "${Date()}\nSOL-Watch Pocket ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                        "Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL}\n" +
                        "Thread: ${thread.name}\n\n" + Log.getStackTraceString(error)
                )
                app.startActivity(Intent(app, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            if (previous != null) previous.uncaughtException(thread, error) else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun isCrashProcess(): Boolean = Application.getProcessName().endsWith(PROCESS_SUFFIX)
}

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReport.file(this).takeIf { it.exists() }?.readText() ?: "No crash report saved."
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            setBackgroundColor(Color.rgb(10, 14, 22))
        }
        layout.addView(TextView(this).apply {
            text = "SOL-Watch Pocket stopped"
            textSize = 20f
            setTextColor(Color.rgb(226, 184, 74))
        })
        layout.addView(TextView(this).apply {
            text = "Copy this report and paste it into the chat, so the cause can be fixed."
            setTextColor(Color.rgb(230, 235, 242))
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SOL-Watch crash", report))
                text = "Copied"
            }
        })
        buttons.addView(Button(this).apply {
            text = "Try again"
            setOnClickListener {
                CrashReport.file(this@CrashActivity).delete()
                startActivity(Intent(this@CrashActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        })
        layout.addView(buttons)
        layout.addView(ScrollView(this).apply {
            addView(TextView(this@CrashActivity).apply {
                text = report
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextIsSelectable(true)
                setTextColor(Color.rgb(147, 160, 180))
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
    }
}
