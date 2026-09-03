package com.lifeos.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("lifeos_guest", Context.MODE_PRIVATE) }
    private lateinit var taskContainer: LinearLayout
    private lateinit var tasksStat: TextView
    private lateinit var balanceStat: TextView
    private lateinit var taskInput: EditText
    private lateinit var alarmText: EditText
    private lateinit var expenseContainer: LinearLayout
    private lateinit var expenseNote: EditText
    private lateinit var expenseAmount: EditText

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        taskContainer = findViewById(R.id.taskContainer)
        tasksStat = findViewById(R.id.tasksStat)
        balanceStat = findViewById(R.id.balanceStat)
        taskInput = findViewById(R.id.taskInput)
        alarmText = findViewById(R.id.alarmText)
        expenseContainer = findViewById(R.id.expenseContainer)
        expenseNote = findViewById(R.id.expenseNote)
        expenseAmount = findViewById(R.id.expenseAmount)

        findViewById<TextView>(R.id.dateText).text =
            java.text.SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id", "ID")).format(Date())

        createChannel()
        findViewById<Button>(R.id.addTaskButton).setOnClickListener { addTask() }
        findViewById<Button>(R.id.notificationButton).setOnClickListener { requestNotifications() }
        findViewById<Button>(R.id.alarmButton).setOnClickListener { setAlarmOneMinute() }
        findViewById<Button>(R.id.addExpenseButton).setOnClickListener { addTransaction(isExpense = true) }
        findViewById<Button>(R.id.addIncomeButton).setOnClickListener { addTransaction(isExpense = false) }

        renderTasks()
        renderTransactions()
        updateBalance()
    }

    private fun addTransaction(isExpense: Boolean) {
        val note = expenseNote.text.toString().trim().ifEmpty { if (isExpense) "Pengeluaran" else "Pemasukan" }
        val rawAmount = expenseAmount.text.toString().trim()
        val amount = rawAmount.toLongOrNull()
        if (amount == null || amount <= 0L) {
            Toast.makeText(this, "Masukkan jumlah yang valid.", Toast.LENGTH_SHORT).show()
            return
        }
        val signedAmount = if (isExpense) -amount else amount
        val current = prefs.getString("transactions", "") ?: ""
        val entry = "${System.currentTimeMillis()}|$note|$signedAmount"
        val next = if (current.isEmpty()) entry else "$current\n$entry"
        prefs.edit().putString("transactions", next).apply()

        val newBalance = prefs.getLong("balance", 0L) + signedAmount
        prefs.edit().putLong("balance", newBalance).apply()

        expenseNote.text.clear()
        expenseAmount.text.clear()
        renderTransactions()
        updateBalance()
    }

    private fun renderTransactions() {
        expenseContainer.removeAllViews()
        val raw = prefs.getString("transactions", "") ?: ""
        val entries = raw.split("\n").filter { it.isNotBlank() }
        val currency = NumberFormat.getCurrencyInstance(Locale("id", "ID"))
        entries.takeLast(15).reversed().forEachIndexed { _, line ->
            val parts = line.split("|")
            if (parts.size != 3) return@forEachIndexed
            val note = parts[1]
            val amount = parts[2].toLongOrNull() ?: 0L

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, 8, 0, 8)

            val noteView = TextView(this)
            noteView.text = note
            noteView.setTextColor(0xFFFFFFFF.toInt())
            noteView.textSize = 14f
            noteView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val amountView = TextView(this)
            amountView.text = (if (amount >= 0) "+" else "") + currency.format(amount)
            amountView.setTextColor(if (amount >= 0) 0xFF4ADE80.toInt() else 0xFFF87171.toInt())
            amountView.textSize = 14f

            row.addView(noteView)
            row.addView(amountView)
            expenseContainer.addView(row)
        }
    }

    private fun addTask() {
        val value = taskInput.text.toString().trim()
        if (value.isEmpty()) return
        val current = prefs.getString("tasks", "") ?: ""
        val next = if (current.isEmpty()) value else "$current\n$value"
        prefs.edit().putString("tasks", next).apply()
        taskInput.text.clear()
        renderTasks()
    }

    private fun renderTasks() {
        taskContainer.removeAllViews()
        val items = (prefs.getString("tasks", "") ?: "").split("\n").filter { it.isNotBlank() }
        tasksStat.text = "TASKS\n${items.size}"
        items.forEachIndexed { index, text ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, 12, 0, 12)
            val check = CheckBox(this)
            check.text = text
            check.setTextColor(0xFFFFFFFF.toInt())
            check.textSize = 16f
            check.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            val del = Button(this)
            del.text = "Hapus"
            del.setOnClickListener {
                val updated = items.toMutableList().also { it.removeAt(index) }
                prefs.edit().putString("tasks", updated.joinToString("\n")).apply()
                renderTasks()
            }
            row.addView(check)
            row.addView(del)
            taskContainer.addView(row)
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Toast.makeText(this, "Notifikasi sudah diizinkan.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("lifeos_alarm", "Life OS Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alarm dan reminder Life OS"
                enableVibration(true)
            }
        )
    }

    private fun setAlarmOneMinute() {
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                Toast.makeText(this, "Izinkan alarm presisi, lalu tekan tombol alarm lagi.", Toast.LENGTH_LONG).show()
                return
            }
        }
        val label = alarmText.text.toString().trim().ifEmpty { "Reminder Life OS" }
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("label", label)
        }
        val pi = PendingIntent.getBroadcast(
            this, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val trigger = System.currentTimeMillis() + 60_000
        getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, trigger, pi
        )
        Toast.makeText(this, "Alarm disetel 1 menit lagi.", Toast.LENGTH_SHORT).show()
        alarmText.text.clear()
    }

    private fun updateBalance() {
        val balance = prefs.getLong("balance", 0L)
        balanceStat.text = "SALDO\n" + NumberFormat.getCurrencyInstance(Locale("id","ID")).format(balance)
    }
}
