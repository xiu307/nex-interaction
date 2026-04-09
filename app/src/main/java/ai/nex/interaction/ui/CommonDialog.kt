package ai.nex.interaction.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import ai.nex.interaction.ui.common.BaseActivity
import ai.nex.interaction.ui.common.BaseDialogFragment
import ai.nex.interaction.databinding.DialogCommonLayoutBinding

class CommonDialog : BaseDialogFragment<DialogCommonLayoutBinding>() {

    // Use data class for dialog configuration
    private data class DialogConfig(
        val title: String? = null,
        val content: String? = null,
        val positiveText: String? = null,
        val negativeText: String? = null,
        val showNegative: Boolean = true,
        val cancelable: Boolean = true,
        val immersiveMode: BaseActivity.ImmersiveMode = BaseActivity.ImmersiveMode.SEMI_IMMERSIVE,
        val onPositiveClick: (() -> Unit)? = null,
        val onNegativeClick: (() -> Unit)? = null
    )

    private var config: DialogConfig = DialogConfig()

    override fun immersiveMode(): BaseActivity.ImmersiveMode = config.immersiveMode

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): DialogCommonLayoutBinding {
        return DialogCommonLayoutBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDialog()
    }

    private fun setupDialog() {
        mBinding?.apply {
            // Set dialog width to 84% of screen width using extension function
            root.setDialogWidth(0.84f)

            // Setup views using apply and let for null safety
            setupBasicViews()
            setupClickListeners()
        }
    }

    private fun DialogCommonLayoutBinding.setupBasicViews() {
        tvTitle.text = config.title
        tvContent.text = config.content
        btnPositive.text = config.positiveText
        btnNegative.text = config.negativeText
        btnNegative.isVisible = config.showNegative
    }

    private fun DialogCommonLayoutBinding.setupClickListeners() {
        btnPositive.setOnClickListener {
            config.onPositiveClick?.invoke()

            dismiss()
        }

        btnNegative.setOnClickListener {
            config.onNegativeClick?.invoke()
            dismiss()
        }
    }

    class Builder {
        private var config = DialogConfig()

        fun setTitle(title: String) = apply { config = config.copy(title = title) }
        fun setContent(content: String) = apply { config = config.copy(content = content) }

        fun setPositiveButton(text: String, onClick: (() -> Unit)? = null) = apply {
            config = config.copy(positiveText = text, onPositiveClick = onClick,)
        }

        fun setNegativeButton(text: String, onClick: (() -> Unit)? = null) = apply {
            config = config.copy(negativeText = text, onNegativeClick = onClick, showNegative = true)
        }

        fun setCancelable(cancelable: Boolean) = apply { config = config.copy(cancelable = cancelable) }

        fun setImmersiveMode(mode: BaseActivity.ImmersiveMode) = apply {
            config = config.copy(immersiveMode = mode)
        }

        fun build(): CommonDialog = CommonDialog().apply {
            this@apply.config = this@Builder.config
            this@apply.isCancelable = config.cancelable
        }
    }
} 