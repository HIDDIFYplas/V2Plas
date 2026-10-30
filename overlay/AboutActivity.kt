// DEST: ui/AboutActivity.kt
package com.v2ray.ang.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.V2PlasLinks
import com.v2ray.ang.ui.compose.VersionInfoBlock
import com.v2ray.ang.util.Utils

class AboutActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        AboutScreen(onBackClick = { finish() })
    }
}

@Composable
fun AboutScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = "درباره برنامه",
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_telegram_24dp),
                title = "کانال تلگرام",
                subtitle = V2PlasLinks.TELEGRAM_ID,
                onClick = { Utils.openUri(context, V2PlasLinks.TELEGRAM_URL) }
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_source_code_24dp),
                title = "کد منبع",
                subtitle = "github.com/HIDDIFYplas/V2Plas",
                onClick = { Utils.openUri(context, V2PlasLinks.SOURCE_URL) }
            )
            VersionInfoBlock(versionText = "V2 Plas v${BuildConfig.VERSION_NAME}")
            Text(
                text = "بر پایهٔ پروژهٔ متن‌باز v2rayNG",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            NavigationBarsSpacer()
        }
    }
}
