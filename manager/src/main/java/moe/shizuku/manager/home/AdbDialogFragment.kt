package moe.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.databinding.AdbDialogBinding
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsPage

@RequiresApi(Build.VERSION_CODES.R)
class AdbDialogFragment : DialogFragment() {

    private lateinit var binding: AdbDialogBinding
    private lateinit var adbMdns: AdbMdns
    private var giveUpJob: Job? = null
    /** The discovered address and port together; the address is what the connection must use. */
    private val endpoint = MutableLiveData<Pair<String, Int>>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = AdbDialogBinding.inflate(layoutInflater)
        adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) {
            endpoint.postValue(it)
        }

        val builder = MaterialAlertDialogBuilder(context).apply {
            setTitle(R.string.dialog_adb_discovery)
            setView(binding.root)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(R.string.development_settings, null)
        }
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener { onDialogShow(dialog) }
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        giveUpJob?.cancel()
        adbMdns.stop()
    }

    private fun onDialogShow(dialog: AlertDialog) {
        adbMdns.start()
        val context = dialog.context
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            SettingsPage.Developer.HighlightWirelessDebugging.launch(context)
        }

        // A search that finds nothing must stop looking like one that is still running.
        giveUpJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(DISCOVERY_GIVE_UP_MS)
            if (isAdded) {
                Log.i(TAG, "Wireless discovery: nothing advertised after ${DISCOVERY_GIVE_UP_MS}ms")
                binding.notFound.visibility = View.VISIBLE
            }
        }

        endpoint.observe(this) { (host, port) ->
            if (port > 65535 || port < 1) return@observe
            endpoint.removeObservers(this)
            giveUpJob?.cancel()
            startAndDismiss(host, port)
        }
    }

    private fun startAndDismiss(host: String, port: Int) {
        val intent = Intent(context, StarterActivity::class.java).apply {
            // The address the mDNS record named, not loopback: on some releases adbd answers on
            // the device's local-network address and a loopback connect fails.
            putExtra(StarterActivity.EXTRA_HOST, host)
            putExtra(StarterActivity.EXTRA_PORT, port)
            putExtra(StarterActivity.EXTRA_REQUIRE_TLS, true)
        }
        requireContext().startActivity(intent)

        dismissAllowingStateLoss()
    }

    fun show(fragmentManager: FragmentManager) {
        if (fragmentManager.isStateSaved) return
        show(fragmentManager, javaClass.simpleName)
    }

    companion object {
        private const val TAG = "AdbDialogFragment"

        /**
         * How long the search may stay quiet before the dialog says nothing was found. Wireless
         * debugging advertises within seconds of being enabled, so this is long past it, and short
         * enough that a user is not left staring at a spinner. Not a measured figure.
         */
        internal const val DISCOVERY_GIVE_UP_MS = 30_000L
    }
}
