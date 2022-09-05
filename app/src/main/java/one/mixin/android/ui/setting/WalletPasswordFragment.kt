package one.mixin.android.ui.setting

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import dagger.hilt.android.AndroidEntryPoint
import one.mixin.android.Constants
import one.mixin.android.R
import one.mixin.android.databinding.FragmentWalletPasswordBinding
import one.mixin.android.extension.clickVibrate
import one.mixin.android.extension.highlightStarTag
import one.mixin.android.extension.replaceFragment
import one.mixin.android.extension.tickVibrate
import one.mixin.android.extension.toast
import one.mixin.android.extension.withArgs
import one.mixin.android.ui.common.BaseFragment
import one.mixin.android.ui.tip.Processing
import one.mixin.android.ui.tip.TipBundle
import one.mixin.android.ui.tip.TipFragment
import one.mixin.android.ui.tip.TipFragment.Companion.ARGS_TIP_BUNDLE
import one.mixin.android.ui.tip.getTipBundle
import one.mixin.android.util.viewBinding
import one.mixin.android.widget.Keyboard
import one.mixin.android.widget.PinView

@AndroidEntryPoint
class WalletPasswordFragment : BaseFragment(R.layout.fragment_wallet_password), PinView.OnPinListener {

    companion object {
        const val TAG = "WalletPasswordFragment"

        private const val STEP1 = 0
        private const val STEP2 = 1
        private const val STEP3 = 2
        private const val STEP4 = 3

        fun newInstance(tipBundle: TipBundle): WalletPasswordFragment {
            return WalletPasswordFragment().withArgs {
                putParcelable(ARGS_TIP_BUNDLE, tipBundle)
            }
        }
    }

    private val binding by viewBinding(FragmentWalletPasswordBinding::bind)

    private var step = STEP1

    private var lastPassword: String? = null

    private val tipBundle: TipBundle by lazy { requireArguments().getTipBundle() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding.apply {
            if (tipBundle.forChange()) {
                titleView.setSubTitle(getString(R.string.Set_new_PIN), "2/5")
                tipTv.text = getString(R.string.wallet_password_set_new_pin_desc)
            } else {
                titleView.setSubTitle(getString(R.string.Set_PIN), "1/4")
                val url = Constants.HelpLink.TIP
                val desc = requireContext().getString(R.string.wallet_password_set_pin_desc)
                tipTv.highlightStarTag(desc, arrayOf(url))
            }
            titleView.leftIb.setOnClickListener {
                when (step) {
                    STEP1 -> activity?.onBackPressedDispatcher?.onBackPressed()
                    STEP2 -> toStep1()
                    STEP3 -> toStep2()
                    STEP4 -> toStep3()
                }
            }
            disableTitleRight()
            titleView.rightAnimator.setOnClickListener { createPin() }
            pin.setListener(this@WalletPasswordFragment)
            keyboard.setKeyboardKeys(Constants.KEYS)
            keyboard.setOnClickKeyboardListener(keyboardListener)
            keyboard.animate().translationY(0f).start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onUpdate(index: Int) {
        if (index == binding.pin.getCount()) {
            binding.titleView.rightTv.setTextColor(resources.getColor(R.color.colorBlue, null))
            binding.titleView.rightAnimator.isEnabled = true
        } else {
            disableTitleRight()
        }
    }

    override fun onBackPressed(): Boolean {
        when (step) {
            STEP1 -> return false
            STEP2 -> {
                toStep1()
                return true
            }
            STEP3 -> {
                toStep2()
                return true
            }
            STEP4 -> {
                toStep3()
                return true
            }
            else -> return false
        }
    }

    private fun disableTitleRight() {
        binding.titleView.apply {
            rightTv.setTextColor(resources.getColor(R.color.text_gray, null))
            rightAnimator.isEnabled = false
        }
    }

    private fun toStep1() {
        step = STEP1
        lastPassword = null
        binding.pin.clear()
        binding.titleView.setSubTitle(
            getString(if (tipBundle.forChange()) R.string.Set_new_PIN else R.string.Set_PIN),
            getSubTitle()
        )
        if (tipBundle.forChange()) {
            binding.tipTv.text = getString(R.string.wallet_password_set_new_pin_desc)
        } else {
            val url = Constants.HelpLink.TIP
            val desc = requireContext().getString(R.string.wallet_password_set_pin_desc)
            binding.tipTv.highlightStarTag(desc, arrayOf(url))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun toStep2(check: Boolean = false) {
        if (check && !validatePin()) {
            binding.pin.clear()
            return
        }

        step = STEP2
        lastPassword = binding.pin.code()
        binding.apply {
            pin.clear()
            titleView.setSubTitle(getString(R.string.Confirm_PIN), getSubTitle())
            tipTv.text = "${getString(R.string.pin_confirm_hint)}\n${getString(R.string.pin_lost_hint)}"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun toStep3(check: Boolean = false) {
        if (check && checkEqual()) return

        step = STEP3
        binding.apply {
            pin.clear()
            titleView.setSubTitle(getString(R.string.Confirm_PIN), getSubTitle())
            tipTv.text = "${getString(R.string.pin_confirm_again_hint)}\n${getString(R.string.third_pin_confirm_hint)}"
        }
    }

    private fun toStep4(check: Boolean = false) {
        if (check && checkEqual()) return

        step = STEP4
        binding.apply {
            pin.clear()
            titleView.setSubTitle(getString(R.string.Confirm_PIN), getSubTitle())
            tipTv.text = getString(R.string.fourth_pin_confirm_hint)
        }
    }

    private fun getSubTitle(): String {
        val change = tipBundle.forChange()
        return when (step) {
            STEP1 -> if (change) "2/5" else "1/4"
            STEP2 -> if (change) "3/5" else "2/4"
            STEP3 -> if (change) "4/5" else "3/4"
            STEP4 -> if (change) "5/5" else "4/4"
            else -> throw IllegalArgumentException("")
        }
    }

    private fun validatePin(): Boolean {
        val pin = binding.pin.code()
        if (pin == "123456") {
            toast(R.string.wallet_password_unsafe)
            return false
        }

        val numKind = arrayListOf<Char>()
        pin.forEach {
            if (!numKind.contains(it)) {
                numKind.add(it)
            }
        }
        if (numKind.size <= 2) {
            toast(R.string.wallet_password_unsafe)
            return false
        }

        return true
    }

    private fun createPin() {
        when (step) {
            STEP1 -> toStep2(true)
            STEP2 -> toStep3(true)
            STEP3 -> toStep4(true)
            STEP4 -> {
                if (checkEqual()) return

                val pin = binding.pin.code()
                tipBundle.pin = pin
                tipBundle.tipStep = Processing.Creating
                val tipFragment = TipFragment.newInstance(tipBundle)
                activity?.replaceFragment(tipFragment, R.id.container, TipFragment.TAG)
            }
        }
    }

    private fun checkEqual(): Boolean {
        if (lastPassword != binding.pin.code()) {
            toast(R.string.wallet_password_not_equal)
            toStep1()
            return true
        }
        return false
    }

    private val keyboardListener: Keyboard.OnClickKeyboardListener = object : Keyboard.OnClickKeyboardListener {
        override fun onKeyClick(position: Int, value: String) {
            context?.tickVibrate()
            if (position == 11) {
                binding.pin.delete()
            } else {
                binding.pin.append(value)
            }
        }

        override fun onLongClick(position: Int, value: String) {
            context?.clickVibrate()
            if (position == 11) {
                binding.pin.clear()
            } else {
                binding.pin.append(value)
            }
        }
    }
}
