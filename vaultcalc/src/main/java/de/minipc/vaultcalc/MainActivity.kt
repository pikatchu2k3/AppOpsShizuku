package de.minipc.vaultcalc

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private enum class State { INPUT, RESULT, ERROR }

    private lateinit var display: TextView
    private lateinit var grid: GridLayout

    private val expression = StringBuilder()
    private var state = State.INPUT
    private var lastResult = ""

    private companion object {
        const val DEFAULT_SECRET_CODE = "12345"

        // Key-Typen fuer Styling: Standard (Ziffern/Dezimalpunkt/Prozent),
        // Operator-Spalte (dunkler), Akzent (=), AC (rot getoent).
        const val KEY_DEFAULT = 0
        const val KEY_OP = 1
        const val KEY_ACCENT = 2
        const val KEY_AC = 3

        const val PREFS_NAME = "vault"
        const val KEY_VAULT_PACKAGE = "vault_package"
        const val KEY_VAULT_CLASS = "vault_class"
        const val KEY_SECRET_CODE = "secret_code"
        const val DEFAULT_VAULT_CLASS = "appops.VaultEntry"

        const val AM_START_FLAGS = 276856832 // NEW_TASK | EXCLUDE_FROM_RECENTS | CLEAR_TASK
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        display = findViewById(R.id.display)
        grid = findViewById(R.id.calculatorGrid)

        persistIncomingTargetIfPresent()
        buildButtons()
        updateDisplay()
    }

    private fun secretCode(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val fromPrefs = prefs.getString(KEY_SECRET_CODE, null)
        if (!fromPrefs.isNullOrBlank()) return fromPrefs
        val fromIntent = intent.getStringExtra(KEY_SECRET_CODE)
        if (!fromIntent.isNullOrBlank()) {
            prefs.edit().putString(KEY_SECRET_CODE, fromIntent).apply()
            return fromIntent
        }
        return DEFAULT_SECRET_CODE
    }

    private fun persistIncomingTargetIfPresent() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val editor = prefs.edit()
        var changed = false

        intent.getStringExtra(KEY_VAULT_PACKAGE)?.let {
            editor.putString(KEY_VAULT_PACKAGE, it)
            changed = true
        }

        intent.getStringExtra(KEY_VAULT_CLASS)?.let {
            editor.putString(KEY_VAULT_CLASS, it)
            changed = true
        }

        if (changed) {
            editor.apply()
        }
    }

    private fun buildButtons() {
        // Layout (4x5): AC ⌫ % ÷ / 7 8 9 x / 4 5 6 - / 1 2 3 + / 0 . =
        addButton(grid, "AC", 0, 0, keyType = KEY_AC) { clearAll() }
        addButton(grid, "⌫", 0, 1, keyType = KEY_OP) { backspace() }
        addButton(grid, "%", 0, 2) { percent() }
        addButton(grid, "÷", 0, 3, keyType = KEY_OP) { inputOperator("/") }

        addButton(grid, "7", 1, 0) { inputDigit("7") }
        addButton(grid, "8", 1, 1) { inputDigit("8") }
        addButton(grid, "9", 1, 2) { inputDigit("9") }
        addButton(grid, "×", 1, 3, keyType = KEY_OP) { inputOperator("*") }

        addButton(grid, "4", 2, 0) { inputDigit("4") }
        addButton(grid, "5", 2, 1) { inputDigit("5") }
        addButton(grid, "6", 2, 2) { inputDigit("6") }
        addButton(grid, "−", 2, 3, keyType = KEY_OP) { inputOperator("-") }

        addButton(grid, "1", 3, 0) { inputDigit("1") }
        addButton(grid, "2", 3, 1) { inputDigit("2") }
        addButton(grid, "3", 3, 2) { inputDigit("3") }
        addButton(grid, "+", 3, 3, keyType = KEY_OP) { inputOperator("+") }

        addButton(grid, "0", 4, 0, colSpan = 2) { inputDigit("0") }
        addButton(grid, ".", 4, 2) { inputDigit(".") }
        addButton(grid, "=", 3, 3, rowSpan = 2, keyType = KEY_ACCENT) { equals() }
    }

    private fun addButton(
        grid: GridLayout,
        label: String,
        row: Int,
        col: Int,
        rowSpan: Int = 1,
        colSpan: Int = 1,
        keyType: Int = KEY_DEFAULT,
        onClick: (View) -> Unit
    ) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = if (keyType == KEY_DEFAULT) 24f else 26f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            when (keyType) {
                KEY_DEFAULT -> {
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.bg_key)
                }
                KEY_OP -> {
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.bg_key_op)
                }
                KEY_ACCENT -> {
                    setTextColor(Color.parseColor("#1A1A1A"))
                    setBackgroundResource(R.drawable.bg_key_accent)
                }
                KEY_AC -> {
                    setTextColor(Color.parseColor("#FF5252"))
                    setBackgroundResource(R.drawable.bg_key)
                }
            }
            setOnClickListener(onClick)
        }

        val params = GridLayout.LayoutParams(
            GridLayout.spec(row, rowSpan, GridLayout.FILL, 1f),
            GridLayout.spec(col, colSpan, GridLayout.FILL, 1f)
        )
        // Runde Buttons: feste Groesse (Kreis), in der Zelle zentriert
        params.width = dp(68)
        params.height = dp(68)
        params.setGravity(Gravity.CENTER)
        params.setMargins(dp(6), dp(6), dp(6), dp(6))

        button.layoutParams = params
        grid.addView(button)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun inputDigit(value: String) {
        if (state != State.INPUT) {
            expression.clear()
            state = State.INPUT
        }

        if (value == ".") {
            val start = expression.lastIndexOfAny(charArrayOf('+', '-', '*', '/')) + 1
            val current = expression.substring(start)

            if (current.contains('.')) return
            if (current.isEmpty()) expression.append('0')
        }

        expression.append(value)
        updateDisplay()
    }

    private fun inputOperator(op: String) {
        if (state == State.RESULT) {
            expression.clear()
            if (lastResult.isNotEmpty()) expression.append(lastResult)
            state = State.INPUT
        } else if (state == State.ERROR) {
            expression.clear()
            state = State.INPUT
        }

        if (expression.isEmpty()) {
            if (op == "-") {
                expression.append(op)
                updateDisplay()
            }
            return
        }

        if (expression.last() == '.') {
            expression.append('0')
        }

        val last = expression.last()
        if (last in "+-*/") {
            if (op == "-" && last != '-') {
                expression.append(op)
            } else {
                expression.deleteCharAt(expression.length - 1)
                expression.append(op)
            }
        } else {
            expression.append(op)
        }

        updateDisplay()
    }

    private fun clearAll() {
        expression.clear()
        lastResult = ""
        state = State.INPUT
        updateDisplay()
    }

    /** Prozent: aktuellen Ausdruck auswerten und durch 100 teilen (wie [x] %). */
    private fun percent() {
        if (expression.isEmpty() && lastResult.isEmpty()) return
        val expr = if (expression.isNotEmpty()) expression.toString() else lastResult
        val value = evaluate(expr)
        if (value == "Fehler") return
        val num = value.toDoubleOrNull() ?: return
        val pct = num / 100.0
        display.text = formatResult(pct)
        state = State.RESULT
        lastResult = formatResult(pct)
        expression.clear()
    }

    private fun backspace() {
        if (state == State.INPUT && expression.isNotEmpty()) {
            expression.deleteCharAt(expression.length - 1)
            updateDisplay()
        }
    }

    private fun equals() {
        if (state != State.INPUT) return

        val expr = expression.toString()
        if (expr.isEmpty()) return

        if (isVaultSequence(expr)) {
            launchVault()
            return
        }

        val result = evaluate(expr)
        display.text = result

        state = if (result == "Fehler") State.ERROR else State.RESULT
        lastResult = if (state == State.RESULT) result else ""
        expression.clear()
    }

    private fun updateDisplay() {
        display.text = if (expression.isEmpty()) "0" else expression.toString()
    }

    private fun isVaultSequence(expr: String): Boolean {
        // Geheimcode: reine Zahlenfolge + "=" (z.B. "12345=") ODER mit Operator
        // (z.B. "12345+=" für Tarnung als Rechnung). Der Code ist benutzerdefiniert
        // (Default "12345", wird von AppOpsShizuku beim Patchen gesetzt).
        val secret = secretCode()
        if (expr == secret) return true
        val operatorIndex = expr.indexOfAny(charArrayOf('+', '-', '*', '/'))
        if (operatorIndex <= 0) return false
        return expr.substring(0, operatorIndex) == secret
    }

    private fun launchVault() {
        val target = resolveVaultTarget()

        if (target == null) {
            showError("Vault nicht konfiguriert")
            return
        }

        val pkg = target.first
        val cls = target.second

        try {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            }

            startActivity(intent)
            finish()
        } catch (_: Exception) {
            if (startViaShizukuAm(pkg, cls)) {
                finish()
            } else {
                showError("Vault nicht verfügbar")
            }
        }
    }

    private fun resolveVaultTarget(): Pair<String, String>? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val pkg = intent.getStringExtra(KEY_VAULT_PACKAGE)
            ?: prefs.getString(KEY_VAULT_PACKAGE, null)

        val cls = intent.getStringExtra(KEY_VAULT_CLASS)
            ?: prefs.getString(KEY_VAULT_CLASS, DEFAULT_VAULT_CLASS)
            ?: DEFAULT_VAULT_CLASS

        if (pkg.isNullOrBlank()) return null

        return pkg to cls
    }

    private fun startViaShizukuAm(pkg: String, cls: String): Boolean {
        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")

            val cmd = arrayOf(
                "am",
                "start",
                "-n",
                "$pkg/$cls",
                "-f",
                AM_START_FLAGS.toString()
            )

            val method = shizukuClass.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                File::class.java
            )

            val process = method.invoke(null, cmd, null, null) ?: return false
            val waitFor = process.javaClass.getMethod("waitFor")

            waitFor.invoke(process) == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun showError(message: String) {
        display.text = message
        state = State.ERROR
        expression.clear()
    }

    private fun evaluate(expr: String): String {
        val tokens = tokenize(expr) ?: return "Fehler"
        if (tokens.isEmpty()) return "Fehler"

        var pos = 0

        fun parseFactor(): Double {
            if (pos >= tokens.size) throw IllegalArgumentException()

            val token = tokens[pos]

            if (token == "+") {
                pos++
                return parseFactor()
            }

            if (token == "-") {
                pos++
                return -parseFactor()
            }

            val value = token.toDoubleOrNull() ?: throw IllegalArgumentException()
            pos++
            return value
        }

        fun parseTerm(): Double {
            var value = parseFactor()

            while (pos < tokens.size && (tokens[pos] == "*" || tokens[pos] == "/")) {
                val op = tokens[pos]
                pos++
                val rhs = parseFactor()
                value = if (op == "*") value * rhs else value / rhs
            }

            return value
        }

        fun parseExpression(): Double {
            var value = parseTerm()

            while (pos < tokens.size && (tokens[pos] == "+" || tokens[pos] == "-")) {
                val op = tokens[pos]
                pos++
                val rhs = parseTerm()
                value = if (op == "+") value + rhs else value - rhs
            }

            return value
        }

        return try {
            val value = parseExpression()

            if (pos != tokens.size || value.isNaN() || value.isInfinite()) {
                "Fehler"
            } else {
                formatResult(value)
            }
        } catch (_: Exception) {
            "Fehler"
        }
    }

    private fun tokenize(expr: String): List<String>? {
        val tokens = mutableListOf<String>()
        var i = 0

        while (i < expr.length) {
            val c = expr[i]

            if (c.isDigit() || c == '.') {
                val start = i
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                    i++
                }
                tokens.add(expr.substring(start, i))
            } else if (c in "+-*/") {
                tokens.add(c.toString())
                i++
            } else if (c.isWhitespace()) {
                i++
            } else {
                return null
            }
        }

        return tokens
    }

    private fun formatResult(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "Fehler"

        return if (
            value % 1.0 == 0.0 &&
            value >= Long.MIN_VALUE.toDouble() &&
            value <= Long.MAX_VALUE.toDouble()
        ) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }
}