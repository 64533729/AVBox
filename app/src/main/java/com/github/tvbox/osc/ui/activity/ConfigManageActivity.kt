package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.page.ConfigManageScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.handleLocalConfigResult
import com.github.tvbox.osc.util.handleLocalSourceTreeResult
import com.github.tvbox.osc.util.startLocalConfig

class ConfigManageActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ConfigManageActivity::class.java))
        }
    }

    private val localConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handleLocalConfigResult(this, uri) { needTree -> if (needTree) settleUnreachableSource(uri) }
            }
        }

    private val sourceTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            handleLocalSourceTreeResult(this, uri)
        }

    fun launchLocalConfig(onResult: (api: String) -> Unit) {
        startLocalConfig(localConfigLauncher) { api -> onResult(api) }
    }

    private fun settleUnreachableSource(uri: Uri) {
        if (PermissionHelper.isStorageGranted(this)) {
            sourceTreeLauncher.launch(null)
            return
        }
        PermissionHelper.requestStorage(this) { granted, _ ->
            if (granted.isNullOrEmpty()) {
                sourceTreeLauncher.launch(null)
            } else {
                handleLocalConfigResult(this, uri) { needTree -> if (needTree) sourceTreeLauncher.launch(null) }
            }
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    ConfigManageScreen(onNavigateBack = { finish() })
                }
            }
        }
    }
}
