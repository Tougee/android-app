package one.mixin.android.ui.setting

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.fragment_setting.*
import kotlinx.android.synthetic.main.view_title.view.*
import kotlinx.coroutines.launch
import one.mixin.android.Constants.Debug.WEB_DEBUG
import one.mixin.android.Constants.TEAM_MIXIN_USER_ID
import one.mixin.android.R
import one.mixin.android.extension.defaultSharedPreferences
import one.mixin.android.extension.navTo
import one.mixin.android.extension.putBoolean
import one.mixin.android.extension.toast
import one.mixin.android.session.Session
import one.mixin.android.ui.common.BaseFragment
import one.mixin.android.ui.conversation.ConversationActivity
import one.mixin.android.ui.device.DeviceFragment
import one.mixin.android.widget.DebugClickListener

@AndroidEntryPoint
class SettingFragment : BaseFragment() {
    companion object {
        const val TAG = "SettingFragment"

        fun newInstance() = SettingFragment()
    }

    private val viewModel by viewModels<SettingViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? =
        layoutInflater.inflate(R.layout.fragment_setting, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        title_view.left_ib.setOnClickListener {
            activity?.onBackPressed()
        }
        about_rl.setOnClickListener(object : DebugClickListener() {
            override fun onDebugClick() {
                if (defaultSharedPreferences.getBoolean(WEB_DEBUG, false)) {
                    defaultSharedPreferences.putBoolean(WEB_DEBUG, false)
                    toast(R.string.web_debug_disable)
                } else {
                    defaultSharedPreferences.putBoolean(WEB_DEBUG, true)
                    toast(R.string.web_debug_enable)
                }
            }

            override fun onSingleClick() {
                navTo(AboutFragment.newInstance(), AboutFragment.TAG)
            }
        })
        desktop_rl.setOnClickListener {
            DeviceFragment.newInstance().showNow(parentFragmentManager, DeviceFragment.TAG)
        }
        storage_rl.setOnClickListener {
            navTo(SettingDataStorageFragment.newInstance(), SettingDataStorageFragment.TAG)
        }
        backup_rl.setOnClickListener {
            navTo(BackUpFragment.newInstance(), BackUpFragment.TAG)
        }
        privacy_rl.setOnClickListener {
            navTo(PrivacyFragment.newInstance(), PrivacyFragment.TAG)
        }
        appearance_rl.setOnClickListener {
            navTo(AppearanceFragment.newInstance(), AppearanceFragment.TAG)
        }
        notification_rl.setOnClickListener {
            navTo(NotificationsFragment.newInstance(), NotificationsFragment.TAG)
        }
        share_rl.setOnClickListener {
            val sendIntent = Intent()
            sendIntent.action = Intent.ACTION_SEND
            sendIntent.putExtra(
                Intent.EXTRA_TEXT,
                getString(R.string.setting_share_text, Session.getAccount()?.identityNumber)
            )
            sendIntent.type = "text/plain"
            startActivity(
                Intent.createChooser(
                    sendIntent,
                    resources.getText(R.string.setting_share)
                )
            )
        }
        feedback_rl.setOnClickListener {
            lifecycleScope.launch {
                val userTeamMixin = viewModel.refreshUser(TEAM_MIXIN_USER_ID)
                if (userTeamMixin == null) {
                    toast(R.string.error_data)
                } else {
                    ConversationActivity.show(requireContext(), recipientId = TEAM_MIXIN_USER_ID)
                }
            }
        }
    }
}
