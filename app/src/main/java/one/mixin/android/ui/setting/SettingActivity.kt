package one.mixin.android.ui.setting

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import dagger.hilt.android.AndroidEntryPoint
import one.mixin.android.R
import one.mixin.android.api.response.AuthorizationResponse
import one.mixin.android.databinding.ActivityContactBinding
import one.mixin.android.extension.addFragment
import one.mixin.android.extension.replaceFragment
import one.mixin.android.ui.common.BlazeBaseActivity
import one.mixin.android.util.viewBinding
import one.mixin.android.vo.App

@AndroidEntryPoint
class SettingActivity : BlazeBaseActivity() {
    companion object {
        const val FROM_NOTIFICATION = "notification"
        const val EXTRA_SHOW_PIN_SETTING = "extra_show_pin_setting"
        const val EXTRA_EMERGENCY_CONTACT = "extra_emergency_contact"
        const val EXTRA_SHOW_PERMISSION_LIST = "extra_show_permission_list"
        const val EXTRA_SHOW_PIN_CHANGE = "extra_show_pin_change"
        const val EXTRA_SHOW_COMPOSE = "extra_show_compose"
        const val EXTRA_APP = "extra_app"
        const val EXTRA_AUTH = "extra_auth"
        const val EXTRA_NODE_LIST_JSON = "extra_node_list_json"
        const val EXTRA_NODE_COUNTER = "extra_node_counter"
        const val ARGS_SUCCESS = "args_success"

        fun show(context: Context, compose: Boolean = true) {
            context.startActivity(
                Intent(context, SettingActivity::class.java).apply {
                    putExtra(EXTRA_SHOW_COMPOSE, compose)
                }
            )
        }

        fun showPinSetting(context: Context) {
            context.startActivity(
                Intent(context, SettingActivity::class.java).apply {
                    putExtra(EXTRA_SHOW_PIN_SETTING, true)
                }
            )
        }

        fun showEmergencyContact(context: Context) {
            context.startActivity(
                Intent(context, SettingActivity::class.java).apply {
                    putExtra(EXTRA_EMERGENCY_CONTACT, true)
                }
            )
        }

        fun showPinChange(context: Context, nodeListJson: String? = null, nodeCounter: Int = 0) {
            context.startActivity(
                Intent(context, SettingActivity::class.java).apply {
                    putExtra(EXTRA_SHOW_PIN_CHANGE, true)
                    nodeListJson?.let { putExtra(EXTRA_NODE_LIST_JSON, it) }
                    putExtra(EXTRA_NODE_COUNTER, nodeCounter)
                }
            )
        }
    }

    private val binding by viewBinding(ActivityContactBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        if (intent.getBooleanExtra(EXTRA_SHOW_PIN_SETTING, false)) {
            replaceFragment(PinSettingFragment.newInstance(), R.id.container, PinSettingFragment.TAG)
        } else if (intent.getBooleanExtra(EXTRA_EMERGENCY_CONTACT, false)) {
            replaceFragment(EmergencyContactFragment.newInstance(), R.id.container, EmergencyContactFragment.TAG)
        } else if (intent.getBooleanExtra(EXTRA_SHOW_PERMISSION_LIST, false)) {
            val app = requireNotNull(intent.getParcelableExtra<App>(EXTRA_APP))
            val auth = requireNotNull(intent.getParcelableExtra<AuthorizationResponse>(EXTRA_AUTH))
            replaceFragment(PermissionListFragment.newInstance(app, auth), R.id.container, PermissionListFragment.TAG)
        } else if (intent.getBooleanExtra(EXTRA_SHOW_COMPOSE, false)) {
            replaceFragment(SettingComposeFragment.newInstance(), R.id.container, SettingComposeFragment.TAG)
        } else if (intent.getBooleanExtra(EXTRA_SHOW_PIN_CHANGE, false)) {
            replaceFragment(OldPasswordFragment.newInstance(
                intent.getStringExtra(EXTRA_NODE_LIST_JSON),
                intent.getIntExtra(EXTRA_NODE_COUNTER, 0),
            ), R.id.container, OldPasswordFragment.TAG)
        } else {
            val fragment = SettingFragment.newInstance()
            replaceFragment(fragment, R.id.container, SettingFragment.TAG)
            if (intent.getBooleanExtra(FROM_NOTIFICATION, false)) {
                addFragment(fragment, BackUpFragment.newInstance(), BackUpFragment.TAG)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val pinSettingFragment =
            supportFragmentManager.findFragmentByTag(PinSettingFragment.TAG) as? PinSettingFragment ?: return
        pinSettingFragment.onActivityResult(requestCode, resultCode, data)
    }

    class PermissionContract : ActivityResultContract<Pair<App, AuthorizationResponse>, Intent?>() {
        override fun createIntent(context: Context, input: Pair<App, AuthorizationResponse>): Intent {
            return Intent(context, SettingActivity::class.java).apply {
                putExtra(EXTRA_SHOW_PERMISSION_LIST, true)
                putExtra(EXTRA_APP, input.first)
                putExtra(EXTRA_AUTH, input.second)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Intent? {
            if (intent == null || resultCode != Activity.RESULT_OK) return null
            return intent
        }
    }
}
