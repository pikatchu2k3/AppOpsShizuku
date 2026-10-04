package de.minipc.appopsshizuku

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * App-Detailseite mit drei Reitern: Berechtigungen / Verhalten / Tarnung.
 * Ersetzt als Einstieg aus der App-Liste die AppOpsActivity.
 */
class AppDetailActivity : AppCompatActivity() {

    companion object {
        /** Gleicher Schluessel wie AppOpsActivity/ComponentsActivity ("pkg"). */
        const val EXTRA_PACKAGE = AppOpsActivity.EXTRA_PACKAGE

        /** Optionaler Start-Reiter (0=Berechtigungen, 1=Verhalten, 2=Tarnung, 3=Netz & Tracker). */
        const val EXTRA_TAB = "tab"

        /** Reiter-Index "Tarnung" (fuer die Fortschritts-Notification des StealthPatchService). */
        const val TAB_STEALTH = 2

        private val TAB_TITLES = intArrayOf(
            R.string.tab_permissions, R.string.tab_behavior, R.string.tab_stealth, R.string.tab_net
        )
    }

    private lateinit var packageName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_detail)

        findViewById<MaterialToolbar>(R.id.toolbar).let { toolbar ->
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setDisplayShowTitleEnabled(true)
        }

        packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish()
            return
        }

        supportActionBar?.title = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }

        val pager = findViewById<ViewPager2>(R.id.viewPager)
        pager.adapter = DetailPagerAdapter()
        // Alle Reiter im Speicher halten: Zustand (Backend, Op-Liste) ueberlebt den Wechsel
        pager.offscreenPageLimit = TAB_TITLES.size - 1
        TabLayoutMediator(findViewById<TabLayout>(R.id.tabLayout), pager) { tab, position ->
            tab.setText(TAB_TITLES[position])
        }.attach()

        // Direkt-Sprung zu einem Reiter (z.B. aus der Fortschritts-Notification des
        // StealthPatchService auf den Reiter "Tarnung").
        val startTab = intent.getIntExtra(EXTRA_TAB, -1)
        if (startTab in 0 until TAB_TITLES.size) {
            pager.setCurrentItem(startTab, false)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private inner class DetailPagerAdapter : FragmentStateAdapter(this) {
        override fun getItemCount() = TAB_TITLES.size

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> PermissionsFragment.newInstance(packageName)
            1 -> BehaviorFragment.newInstance(packageName)
            2 -> StealthFragment.newInstance(packageName)
            else -> NetFragment.newInstance(packageName)
        }
    }
}
